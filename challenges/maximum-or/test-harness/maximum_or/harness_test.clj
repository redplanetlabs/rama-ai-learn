(ns maximum-or.harness-test
  "Validates the test harness against the reference implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [maximum-or.test-support :as support]
   [maximum-or.solution :as ref]))

;; Validates the test harness using the reference implementation.

(deftest harness-detects-success-test
  (testing "harness-detects-success"
    (testing "maximum-or (reference implementation)"
      (support/test-solution ref/maximum-or))))
