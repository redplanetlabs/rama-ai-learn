(ns impartial-gift.harness-test
  "Validates the test harness against the reference implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [impartial-gift.test-support :as support]
   [impartial-gift.solution :as ref]))

;; Validates the test harness using the reference implementation.

(deftest harness-detects-success-test
  (testing "harness-detects-success"
    (testing "solve (reference implementation)"
      (support/test-solution ref/solve))))
