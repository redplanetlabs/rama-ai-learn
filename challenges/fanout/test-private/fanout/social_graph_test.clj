(ns fanout.social-graph-test
  "Tests for the bundled fanout.social-graph module."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.rpl.rama :as r]
   [com.rpl.rama.path :refer :all]
   [com.rpl.rama.test :as rtest]
   [fanout.protocol :as p]
   [fanout.social-graph :as sg]))

(defn- handles [ipc]
  (let [m (r/get-module-name sg/SocialGraphModule)]
    {:depot     (r/foreign-depot   ipc m "*follow-depot")
     :control   (r/foreign-pstate  ipc m "$$partitioned-followers-control")
     :tasks     (r/foreign-pstate  ipc m "$$partitioned-follower-tasks")
     :followees (r/foreign-pstate  ipc m "$$user-followees")
     :followers (r/foreign-query   ipc m "get-followers")}))

(defn- followees-of [followees account-id]
  (set (r/foreign-select [(keypath account-id) ALL] followees {:pkey account-id})))

(defn- follow! [depot account-id target-id]
  (r/foreign-append! depot (p/->FollowAccount account-id target-id) :ack))

(defn- unfollow! [depot account-id target-id]
  (r/foreign-append! depot (p/->RemoveFollowAccount account-id target-id) :ack))

(deftest small-follow-set-is-on-single-task
  (testing "with default cutoff, a small follow set fits on the bootstrap task"
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc sg/SocialGraphModule {:tasks 4 :threads 2})
      (let [{:keys [depot control tasks followees followers]} (handles ipc)]
        (doseq [a (range 1 6)]
          (follow! depot a 999))

        (let [ctrl (r/foreign-select-one (keypath 999) control {:pkey 999})]
          (is (= 1 (count ctrl))
              "fewer than NEW-TASK-CUTOFF followers => one task in control list")
          (let [t (first ctrl)]
            (doseq [a (range 1 6)]
              (is (= t (r/foreign-select-one (keypath 999 a) tasks {:pkey 999}))
                  (str "follower " a " should map to task " t)))))

        (is (= (set (range 1 6))
               (r/foreign-invoke-query followers 999)))

        (testing "$$user-followees records each follower's followee"
          (doseq [a (range 1 6)]
            (is (= #{999} (followees-of followees a))
                (str "account " a " should follow only 999")))
          (is (= #{} (followees-of followees 12345))
              "unknown account has no followees"))

        (testing "unfollow removes from $$partitioned-followers and $$user-followees"
          (unfollow! depot 3 999)
          (is (= (disj (set (range 1 6)) 3)
                 (r/foreign-invoke-query followers 999)))
          (is (= #{} (followees-of followees 3))
              "unfollow clears 3's followee set"))

        (testing "unknown target returns empty followers"
          (is (empty? (r/foreign-invoke-query followers 12345))))))))

(deftest spreads-across-tasks-and-round-robins
  (testing "with a small cutoff, allocation hits 'n' branch repeatedly then 'r' (round-robin)"
    (with-redefs [sg/NEW-TASK-CUTOFF 2]
      (with-open [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc sg/SocialGraphModule {:tasks 4 :threads 2})
        (let [{:keys [depot control tasks followers]} (handles ipc)
              n-followers 12]
          (doseq [a (range 1 (inc n-followers))]
            (follow! depot a 42))

          (let [ctrl    (r/foreign-select-one (keypath 42) control {:pkey 42})
                ctrl-set (set ctrl)]
            (is (= 4 (count ctrl))
                "after numTasks * cutoff = 8 followers all 4 tasks should be in the control list")
            (is (= 4 (count ctrl-set))
                "control list contains distinct task IDs")
            (is (every? #(<= 0 % 3) ctrl-set)
                "every entry in control is a valid task id"))

          (testing "every follower is mapped to a task in the control list"
            (let [ctrl     (set (r/foreign-select-one (keypath 42) control {:pkey 42}))
                  per-task (->> (range 1 (inc n-followers))
                                (map (fn [a]
                                       [a (r/foreign-select-one (keypath 42 a) tasks {:pkey 42})])))]
              (doseq [[a t] per-task]
                (is (contains? ctrl t)
                    (str "follower " a " is on task " t " which must be in control")))
              (is (>= (count (set (map second per-task))) 2)
                  "followers are spread across at least 2 tasks")))

          (testing "get-followers returns every follower across all partitions"
            (is (= (set (range 1 (inc n-followers)))
                   (r/foreign-invoke-query followers 42)))))))))
