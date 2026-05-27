(ns number-of-senior-citizens.harness-test
  "Validates the test harness against the reference implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [number-of-senior-citizens.test-support :as support]
   [number-of-senior-citizens.solution :as ref]))

;; Validates the test harness using the reference implementation.

(deftest harness-detects-success-test
  (testing "harness-detects-success"
    (testing "count-seniors (reference implementation)"
      (support/test-solution ref/count-seniors))))
