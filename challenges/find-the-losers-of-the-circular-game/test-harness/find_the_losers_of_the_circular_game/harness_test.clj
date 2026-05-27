(ns find-the-losers-of-the-circular-game.harness-test
  "Validates the test harness against the reference implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [find-the-losers-of-the-circular-game.test-support :as support]
   [find-the-losers-of-the-circular-game.solution :as ref]))

;; Validates the test harness using the reference implementation.

(deftest harness-detects-success-test
  (testing "harness-detects-success"
    (testing "circular-game-losers (reference implementation)"
      (support/test-solution ref/circular-game-losers))))
