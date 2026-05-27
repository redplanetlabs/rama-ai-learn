(ns sum-in-a-matrix.harness-test
  "Validates the test harness against the reference implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [sum-in-a-matrix.test-support :as support]
   [sum-in-a-matrix.solution :as ref]))

;; Validates the test harness using the reference implementation.

(deftest harness-detects-success-test
  (testing "harness-detects-success"
    (testing "matrix-sum (reference implementation)"
      (support/test-solution ref/matrix-sum))))
