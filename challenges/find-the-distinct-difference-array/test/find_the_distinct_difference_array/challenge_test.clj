(ns find-the-distinct-difference-array.challenge-test
  "Verifies the agent's deframafn implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [find-the-distinct-difference-array.test-support :as support]))

;; Tests verify the agent's distinct-difference-array deframafn.

(deftest challenge-test
  (testing "distinct-difference-array (agent implementation)"
    (support/test-solution
     (requiring-resolve 'find-the-distinct-difference-array.solution/distinct-difference-array))))
