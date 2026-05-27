(ns number-of-adjacent-elements-with-the-same-color.harness-test
  "Validates the test harness against the reference implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [number-of-adjacent-elements-with-the-same-color.test-support :as support]
   [number-of-adjacent-elements-with-the-same-color.solution :as ref]))

;; Validates the test harness using the reference implementation.

(deftest harness-detects-success-test
  (testing "harness-detects-success"
    (testing "color-the-array (reference implementation)"
      (support/test-solution ref/color-the-array))))
