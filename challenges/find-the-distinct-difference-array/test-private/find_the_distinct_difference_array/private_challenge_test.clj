(ns find-the-distinct-difference-array.private-challenge-test
  "Runs hidden/private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [find-the-distinct-difference-array.private-test-support :as support]))

(deftest private-challenge-test
  (testing "distinct-difference-array (agent implementation, private tests)"
    (support/test-solution
     (requiring-resolve 'find-the-distinct-difference-array.solution/distinct-difference-array))))
