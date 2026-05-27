(ns sum-in-a-matrix.challenge-test
  "Verifies the agent's deframafn implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [sum-in-a-matrix.test-support :as support]))

;; Tests verify the agent's matrix-sum deframafn.

(deftest challenge-test
  (testing "matrix-sum (agent implementation)"
    (support/test-solution
     (requiring-resolve 'sum-in-a-matrix.solution/matrix-sum))))
