(ns sum-in-a-matrix.private-challenge-test
  "Runs hidden/private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [sum-in-a-matrix.private-test-support :as support]))

(deftest private-challenge-test
  (testing "matrix-sum (agent implementation, private tests)"
    (support/test-solution
     (requiring-resolve 'sum-in-a-matrix.solution/matrix-sum))))
