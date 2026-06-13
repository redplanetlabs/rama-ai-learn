(ns fanout.performance-test-support
  "Performance and fault-tolerance private tests for fanout. Launches the
   bundled SocialGraphModule alongside the implementation under test."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama :as r]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [rama-challenges.shared :as shared]
   [fanout.protocol :as p]
   [fanout.social-graph :as sg]))

(defn- follow! [follow-depot account-id target-id]
  (r/foreign-append! follow-depot (p/->FollowAccount account-id target-id) :ack))

(defn- paced-post!
  "Post with a 3ms pause so successive UUID7 post-ids land in distinct
   milliseconds, making timeline order deterministic."
  [client account-id content]
  (p/post! client account-id content)
  (Thread/sleep 3))

(defn- walk-pages
  "Page through user-id's entire visible timeline, returning all entries."
  [client user-id]
  (loop [from-post-id nil
         entries []]
    (let [page (p/get-timeline-page client user-id from-post-id)]
      (if (empty? page)
        entries
        (recur (:post-id (last page)) (into entries page))))))

(defn capture-rocks-ops [f]
  (let [state (atom {})]
    (rtest/with-event-hook
      (fn [event-type data]
        (case event-type
          :rocks-read (swap! state update :rocks-read (fnil inc 0))
          :rocks-iterator (swap! state update :rocks-iterator (fnil inc 0))
          :rocks-iterator-read (swap! state update :rocks-iterator-read (fnil inc 0))
          :rocks-commit (swap! state update :rocks-writes (fnil #(+ % (:write-batch-count data)) 0))
          nil))
      (f)
      @state)))

(defn test-module-fault-tolerance
  "Restart recovery: after a module update wipes in-memory state, timeline
   reads must reconstruct from durable state — exactly the same entries, in
   strict most-recent-first post-id order, with current names."
  [create-module-fn]
  (with-redefs [shared/REPLACE-TICK-DEPOTS true]
    (let [{:keys [module wrap-client]} (create-module-fn)
          tasks   (rand-nth [2 4])
          threads (+ 2 (rand-int (inc (- tasks 2))))]
      (binding [harness/*task-count* tasks]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc sg/SocialGraphModule {:tasks tasks :threads threads})
          (rtest/launch-module! ipc module {:tasks tasks :threads threads})
          (let [client       (wrap-client ipc)
                follow-depot (r/foreign-depot ipc
                                              (r/get-module-name sg/SocialGraphModule)
                                              "*follow-depot")
                reader       100
                posters      [101 102 103]
                rounds       10
                ;; round-robin posts: p101-0, p102-0, p103-0, p101-1, ...
                posts        (vec (for [i (range rounds), t posters]
                                    [t (format "p%d-%d" t i)]))
                expected-desc          (vec (reverse (map second posts)))
                expected-accounts-desc (vec (reverse (map first posts)))]
            (doseq [t posters]
              (p/set-profile! client t (str "Name" t) "Loc"))
            (doseq [t posters]
              (follow! follow-depot reader t))

            (doseq [[t content] posts]
              (paced-post! client t content))
            (harness/wait-for-processing! client)

            ;; Simulated restart: in-memory state is lost, durable state and
            ;; depot/PState/query clients survive.
            (rtest/update-module! ipc module)

            (testing "durable reads survive the update"
              (is (= (mapv second (filter #(= 101 (first %)) posts))
                     (p/get-user-timeline client 101))
                  "own post log intact and chronological")
              (is (= {:name "Name101" :location "Loc"}
                     (p/get-profile client 101))))

            (testing "reconstructed timeline is exactly right, in strict order"
              (let [entries (walk-pages client reader)]
                (is (= (count posts) (count entries)))
                (is (= expected-desc (mapv :content entries))
                    "strict most-recent-first content order after reconstruction")
                (is (= expected-accounts-desc (mapv :account-id entries)))
                (is (every? (fn [[a b]] (pos? (compare (:post-id a) (:post-id b))))
                            (partition 2 1 entries))
                    "post-ids strictly descending")
                (is (every? #(= (str "Name" (:account-id %)) (:name %)) entries)
                    "names resolved from current profiles")))

            (testing "fanout still works after the update"
              (paced-post! client 102 "after-update")
              (harness/wait-for-processing! client)
              (let [page (p/get-timeline-page client reader nil)]
                (is (= "after-update" (:content (first page))))
                (is (= 102 (:account-id (first page))))))))))))

(defn test-module-performance
  "Durable write volume must be bounded independently of follower count:
   per-follower PState/depot writes during fanout would fail this test."
  [create-module-fn]
  (with-redefs [shared/REPLACE-TICK-DEPOTS true]
    (let [{:keys [module wrap-client]} (create-module-fn)
          tasks   (rand-nth [2 4])
          threads (+ 2 (rand-int (inc (- tasks 2))))]
      (binding [harness/*task-count* tasks]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc sg/SocialGraphModule {:tasks tasks :threads threads})
          (rtest/launch-module! ipc module {:tasks tasks :threads threads})
          (let [client       (wrap-client ipc)
                follow-depot (r/foreign-depot ipc
                                              (r/get-module-name sg/SocialGraphModule)
                                              "*follow-depot")
                poster      1
                n-followers 500]
            (p/set-profile! client poster "Star" "X")
            (doseq [f (range 10000 (+ 10000 n-followers))]
              (follow! follow-depot f poster))

            (let [info (capture-rocks-ops
                        (fn []
                          (p/post! client poster "hello-followers")
                          (harness/wait-for-processing! client)))]
              (testing "post delivered to every follower's timeline"
                (let [delivered (->> (range 10000 (+ 10000 n-followers))
                                     (filter (fn [f]
                                               (= ["hello-followers"]
                                                  (mapv :content (p/get-timeline-page client f nil)))))
                                     count)]
                  (is (= n-followers delivered)
                      "every follower must see the post in their timeline")))
              (testing "fanout durable write volume is independent of follower count"
                ;; A constant bound: per-follower durable writes (>= 500 here)
                ;; must fail decisively; legitimate designs write O(1) records.
                (is (< (:rocks-writes info 0) 20)
                    (str "fanout must not write to a PState or depot per "
                         "follower; RocksDB ops: " info))))))))))
