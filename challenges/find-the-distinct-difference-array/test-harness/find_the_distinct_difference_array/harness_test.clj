(ns find-the-distinct-difference-array.harness-test
  "Validates the test harness against the reference implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [find-the-distinct-difference-array.test-support :as support]
   [find-the-distinct-difference-array.solution :as ref]))

;; Validates the test harness using the reference implementation.

(deftest harness-detects-success-test
  (testing "harness-detects-success"
    (testing "distinct-difference-array (reference implementation)"
      (support/test-solution ref/distinct-difference-array))))
