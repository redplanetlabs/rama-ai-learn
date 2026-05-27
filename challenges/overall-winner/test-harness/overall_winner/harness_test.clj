(ns overall-winner.harness-test
  "Validates the test harness against the reference implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [overall-winner.test-support :as support]
   [overall-winner.solution :as ref]))

;; Validates the test harness using the reference implementation.

(deftest harness-detects-success-test
  (testing "harness-detects-success"
    (testing "solve (reference implementation)"
      (support/test-solution ref/solve))))
