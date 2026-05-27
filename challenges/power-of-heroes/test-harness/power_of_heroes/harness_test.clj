(ns power-of-heroes.harness-test
  "Validates the test harness against the reference implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [power-of-heroes.test-support :as support]
   [power-of-heroes.solution :as ref]))

;; Validates the test harness using the reference implementation.

(deftest harness-detects-success-test
  (testing "harness-detects-success"
    (testing "sum-of-power (reference implementation)"
      (support/test-solution ref/sum-of-power))))
