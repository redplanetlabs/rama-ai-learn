(ns fanout.functional-test-support
  "Functional private tests for fanout. Launches the bundled SocialGraphModule
   alongside the implementation under test and exercises the public protocol
   plus direct follow events on the social graph."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama :as r]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [rama-challenges.shared :as shared]
   [fanout.protocol :as p]
   [fanout.social-graph :as sg]))

(defn- follow! [follow-depot account-id target-id]
  (r/foreign-append! follow-depot (p/->FollowAccount account-id target-id) :ack))

(defn- unfollow! [follow-depot account-id target-id]
  (r/foreign-append! follow-depot (p/->RemoveFollowAccount account-id target-id) :ack))

(defn- by-account [timeline]
  (group-by :account-id timeline))

(defn test-module-functional
  [create-module-fn]
  (with-redefs [shared/REPLACE-TICK-DEPOTS true]
    (let [{:keys [module wrap-client]} (create-module-fn)
          tasks   (rand-nth [2 4])
          threads (+ 2 (rand-int (inc (- tasks 2))))]
      (binding [harness/*task-count* tasks]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc sg/SocialGraphModule {:tasks tasks :threads threads})
          (rtest/launch-module! ipc module                {:tasks tasks :threads threads})
          (let [client       (wrap-client ipc)
                follow-depot (r/foreign-depot ipc
                                              (r/get-module-name sg/SocialGraphModule)
                                              "*follow-depot")]

            (testing "missing profile returns nil"
              (is (nil? (p/get-profile client 1))))

            (testing "set-profile! / get-profile, including overwrite"
              (p/set-profile! client 1 "Alice" "NYC")
              (is (= {:name "Alice" :location "NYC"} (p/get-profile client 1)))
              (p/set-profile! client 1 "Alicia" "Brooklyn")
              (is (= {:name "Alicia" :location "Brooklyn"} (p/get-profile client 1))))

            (testing "get-user-timeline returns own posts in chronological order"
              (p/post! client 1 "first")
              (p/post! client 1 "second")
              (p/post! client 1 "third")
              (harness/wait-for-processing! client)
              (is (= ["first" "second" "third"] (p/get-user-timeline client 1)))
              (is (= [] (p/get-user-timeline client 9999))
                  "user with no posts returns empty"))

            (testing "cold-cache reconstruction picks up posts made before first read"
              (p/set-profile! client 20 "Bob"   "SF")
              (p/set-profile! client 30 "Carol" "LA")
              (follow! follow-depot 10 20)
              (follow! follow-depot 10 30)

              (p/post! client 20 "bob-1")
              (p/post! client 30 "carol-1")
              (p/post! client 20 "bob-2")
              (harness/wait-for-processing! client)

              (let [page    (p/get-timeline-page client 10 nil)
                    grouped (by-account page)]
                (is (= 3 (count page))
                    "all 3 posts visible after reconstruction")
                (is (= 2 (count (grouped 20))))
                (is (= 1 (count (grouped 30))))
                (is (every? #(= "Bob"   (:name %)) (grouped 20)))
                (is (every? #(= "Carol" (:name %)) (grouped 30)))
                (is (= "bob-2" (:content (first page)))
                    "most recent first"))

              (is (= [] (p/get-timeline-page client 9998 nil))
                  "user with no follows reconstructs to empty timeline"))

            (testing "live fanout after the cache is initialized"
              (p/post! client 20 "bob-3")
              (harness/wait-for-processing! client)

              (let [page (p/get-timeline-page client 10 nil)]
                (is (= 4 (count page)))
                (is (= "bob-3" (:content (first page)))
                    "newest live post comes first")))

            (testing "unfollow stops future fanout but preserves prior entries"
              (p/set-profile! client 60 "Dan" "X")
              (follow! follow-depot 50 60)
              (is (= [] (p/get-timeline-page client 50 nil)) "init 50's timeline")

              (p/post! client 60 "before-unfollow")
              (harness/wait-for-processing! client)

              (unfollow! follow-depot 50 60)
              (p/post! client 60 "after-unfollow")
              (harness/wait-for-processing! client)

              (let [page (p/get-timeline-page client 50 nil)]
                (is (= 1 (count page)))
                (is (= "before-unfollow" (-> page first :content)))))

            (testing "multiple followees deliver in chronological order"
              (doseq [t [100 101 102]]
                (p/set-profile! client t (str "U" t) "X")
                (follow! follow-depot 99 t))
              (is (= [] (p/get-timeline-page client 99 nil)) "init 99's timeline")

              (p/post! client 100 "a")
              (p/post! client 101 "b")
              (p/post! client 102 "c")
              (p/post! client 100 "d")
              (harness/wait-for-processing! client)

              (let [page (p/get-timeline-page client 99 nil)]
                (is (= 4 (count page)))
                (is (= ["d" "c" "b" "a"] (mapv :content page))
                    "most recent first across all followees")
                (is (= [100 102 101 100] (mapv :account-id page)))))

            (testing "poster's own posts appear in their own timeline (self-fanout)"
              (p/set-profile! client 80 "Frank" "X")
              (is (= [] (p/get-timeline-page client 80 nil)) "init 80's timeline (no follows)")

              (p/post! client 80 "frank-1")
              (p/post! client 80 "frank-2")
              (harness/wait-for-processing! client)

              (let [page (p/get-timeline-page client 80 nil)]
                (is (= 2 (count page)))
                (is (= ["frank-2" "frank-1"] (mapv :content page))
                    "most recent first")
                (is (every? #(= 80 (:account-id %)) page))
                (is (every? #(= "Frank" (:name %)) page))))

            (testing "timeline includes both own posts and followee posts"
              (p/set-profile! client 90 "Grace" "X")
              (p/set-profile! client 91 "Hank"  "Y")
              (follow! follow-depot 90 91)
              (is (= [] (p/get-timeline-page client 90 nil)) "init 90's timeline")

              (p/post! client 91 "hank-1")
              (p/post! client 90 "grace-1")
              (p/post! client 91 "hank-2")
              (p/post! client 90 "grace-2")
              (harness/wait-for-processing! client)

              (let [page (p/get-timeline-page client 90 nil)]
                (is (= 4 (count page)))
                (is (= #{"grace-1" "grace-2" "hank-1" "hank-2"}
                       (set (mapv :content page)))
                    "all 4 posts visible — own posts (self-fanout) plus followee posts")
                (is (= #{90 91} (set (mapv :account-id page)))
                    "both poster (self) and followee contribute")))

            (testing "timeline reflects current profile name at read time"
              (p/set-profile! client 70 "Eve" "X")
              (follow! follow-depot 71 70)
              (is (= [] (p/get-timeline-page client 71 nil)) "init 71's timeline")

              (p/post! client 70 "from-eve")
              (harness/wait-for-processing! client)

              (is (= "Eve" (-> (p/get-timeline-page client 71 nil) first :name))
                  "name reflects profile at first read")

              ;; Profile update AFTER the post — subsequent reads should see new name
              (p/set-profile! client 70 "Evelyn" "Y")
              (let [page (p/get-timeline-page client 71 nil)]
                (is (= "Evelyn" (-> page first :name))
                    "name reflects updated profile, not snapshot at post time")
                (is (= "from-eve" (-> page first :content))
                    "content unchanged by profile update")))

            (testing "pagination across one full page and one partial page"
              (p/set-profile! client 300 "P300" "X")
              (follow! follow-depot 299 300)
              (is (= [] (p/get-timeline-page client 299 nil)) "init 299's timeline")

              (dotimes [i 25]
                (p/post! client 300 (format "p25-%02d" i)))
              (harness/wait-for-processing! client)

              (let [page1 (p/get-timeline-page client 299 nil)
                    page2 (p/get-timeline-page client 299 (:post-id (last page1)))
                    page3 (p/get-timeline-page client 299 (:post-id (last page2)))]
                (is (= 20 (count page1)) "first page is full at 20 entries")
                (is (= "p25-24" (:content (first page1))) "first page starts at newest")
                (is (= "p25-05" (:content (last page1))) "first page ends at the 20th-newest")
                (is (= 5 (count page2)) "second page is partial with the remaining 5")
                (is (= ["p25-04" "p25-03" "p25-02" "p25-01" "p25-00"]
                       (mapv :content page2))
                    "second page is the oldest 5, most-recent-first")
                (is (= [] page3) "page after the last has no entries")))

            (testing "timeline is capped at 800 most-recent entries (verified by paging through)"
              (p/set-profile! client 200 "P200" "X")
              (follow! follow-depot 199 200)
              (is (= [] (p/get-timeline-page client 199 nil)) "init 199's timeline")

              (dotimes [i 850]
                (p/post! client 200 (format "post-%03d" i)))
              (harness/wait-for-processing! client)

              (let [all-pages (loop [from-post-id nil
                                     pages        []]
                                (let [page (p/get-timeline-page client 199 from-post-id)]
                                  (if (empty? page)
                                    pages
                                    (recur (:post-id (last page)) (conj pages page)))))
                    contents  (mapv :content (apply concat all-pages))]
                (is (every? #(<= (count %) 20) all-pages)
                    "every page has at most 20 entries")
                (is (= 800 (count contents))
                    "exactly 800 entries retained — older ones evicted")
                (is (= "post-849" (first contents))
                    "newest entry is post-849")
                (is (= "post-050" (last contents))
                    "oldest retained entry is post-050 (850 - 800)")
                (is (= (mapv #(format "post-%03d" %) (range 849 49 -1))
                       contents)
                    "all 800 entries are contiguous most-recent-first")))))))))
