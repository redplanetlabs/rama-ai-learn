(ns rama-challenges.event-assertions-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rama-challenges.event-assertions :as events]))

(deftest match-ordered-allows-subsequence-matches
  (testing "matches ordered subsequences while ignoring unrelated events"
    (let [stream [{:event-type :a :value 1}
                  {:event-type :noise}
                  {:event-type :b :value 2}
                  {:event-type :c :value 3}]
          result (events/match-ordered stream
                                       [{:event-type :a}
                                        {:event-type :c}])]
      (is (:ok? result))
      (is (= [0 3] (mapv :idx (:matches result)))))))

(deftest match-ordered-supports-variable-unification
  (testing "reuses bound vars across later event patterns"
    (let [stream [{:event-type :partitioner :task-id 1 :to-task-id 1}
                  {:event-type :local-transform :task-id 1}
                  {:event-type :send-email :task-id 1}]
          result (events/match-ordered stream
                                       [{:event-type :partitioner
                                         :task-id '?task-id
                                         :to-task-id '?task-id}
                                        {:event-type :local-transform
                                         :task-id '?task-id}
                                        {:event-type :send-email
                                         :task-id '?task-id}])]
      (is (:ok? result))
      (is (= {'?task-id 1} (:bindings result))))))

(deftest match-ordered-supports-bind-clause
  (testing "bind clause captures event fields for later patterns"
    (let [stream [{:event-type :partitioner :task-id 2 :to-task-id 3}
                  {:event-type :local-transform :task-id 3}
                  {:event-type :send-email :task-id 3}]
          result (events/match-ordered stream
                                       [{:event-type :partitioner
                                         :bind {'?source-task :task-id
                                                '?dest-task :to-task-id}}
                                        {:event-type :local-transform
                                         :task-id '?dest-task}
                                        {:event-type :send-email
                                         :task-id '?dest-task}])]
      (is (:ok? result))
      (is (= {'?source-task 2
              '?dest-task 3}
             (:bindings result))))))

(deftest match-ordered-fails-on-binding-mismatch
  (testing "returns a failure result when a later event violates bindings"
    (let [stream [{:event-type :partitioner :task-id 1 :to-task-id 1}
                  {:event-type :local-transform :task-id 2}
                  {:event-type :send-email :task-id 1}]
          result (events/match-ordered stream
                                       [{:event-type :partitioner
                                         :task-id '?task-id
                                         :to-task-id '?task-id}
                                        {:event-type :local-transform
                                         :task-id '?task-id}
                                        {:event-type :send-email
                                         :task-id '?task-id}])]
      (is (false? (:ok? result)))
      (is (= {:event-type :local-transform
              :task-id '?task-id}
             (:failed-pattern result))))))

(deftest assert-ordered-bubbles-up-match-result
  (testing "assert-ordered! returns the same successful result shape"
    (let [stream [{:event-type :partitioner :task-id 1 :to-task-id 1}
                  {:event-type :local-transform :task-id 1}
                  {:event-type :send-email :task-id 1}]
          result (events/assert-ordered! stream
                                         [{:event-type :partitioner
                                           :task-id '?task-id
                                           :to-task-id '?task-id}
                                          {:event-type :local-transform
                                           :task-id '?task-id}
                                          {:event-type :send-email
                                           :task-id '?task-id}])]
      (is (:ok? result))
      (is (= 3 (count (:matches result)))))))
