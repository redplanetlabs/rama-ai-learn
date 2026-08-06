(ns unbalanced-social-graph.functional-test-support
  "Functional private tests for unbalanced-social-graph. These exercise the
   public protocol only and make no assumption about the partitioning
   strategy, so any correct implementation passes."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [unbalanced-social-graph.protocol :as p]))

(defn test-module-functional
  [create-module-fn]
  (let [{:keys [module wrap-client]} (create-module-fn)
        tasks   (rand-nth [2 4])
        threads (+ 2 (rand-int (inc (- tasks 2))))]
    (binding [harness/*task-count* tasks]
      (with-open [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc module {:tasks tasks :threads threads})
        (let [client (wrap-client ipc)]

          (testing "unknown accounts have empty follower/followee sets"
            (is (= #{} (p/get-followers client 12345)))
            (is (= #{} (p/get-followees client 12345))))

          (testing "follows are recorded in both directions"
            (doseq [a (range 1 6)]
              (p/follow! client a 999))
            (harness/wait-for-processing! client)
            (is (= (set (range 1 6)) (p/get-followers client 999))
                "all five followers visible")
            (doseq [a (range 1 6)]
              (is (= #{999} (p/get-followees client a))
                  (str "account " a " follows only 999"))))

          (testing "an account can follow many targets"
            (doseq [t [200 201 202]]
              (p/follow! client 50 t))
            (harness/wait-for-processing! client)
            (is (= #{200 201 202} (p/get-followees client 50)))
            (doseq [t [200 201 202]]
              (is (contains? (p/get-followers client t) 50))))

          (testing "following is idempotent"
            (p/follow! client 1 999)
            (p/follow! client 1 999)
            (harness/wait-for-processing! client)
            (is (= (set (range 1 6)) (p/get-followers client 999))
                "duplicate follow does not change the follower set"))

          (testing "unfollow removes from both directions"
            (p/unfollow! client 3 999)
            (harness/wait-for-processing! client)
            (is (= (disj (set (range 1 6)) 3) (p/get-followers client 999))
                "3 removed from 999's followers")
            (is (= #{} (p/get-followees client 3))
                "999 removed from 3's followees"))

          (testing "unfollowing a pair that does not exist is a no-op"
            (p/unfollow! client 777 888)
            (harness/wait-for-processing! client)
            (is (= #{} (p/get-followers client 888)))
            (is (= (disj (set (range 1 6)) 3) (p/get-followers client 999))
                "unrelated unfollow leaves 999's followers untouched")))))))
