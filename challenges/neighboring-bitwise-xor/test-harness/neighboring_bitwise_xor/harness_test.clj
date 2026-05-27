(ns neighboring-bitwise-xor.harness-test
  "Validates the test harness against the reference implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [neighboring-bitwise-xor.test-support :as support]
   [neighboring-bitwise-xor.solution :as ref]))

;; Validates the test harness using the reference implementation.

(deftest harness-detects-success-test
  (testing "harness-detects-success"
    (testing "does-valid-array-exist (reference implementation)"
      (support/test-solution ref/does-valid-array-exist))))
