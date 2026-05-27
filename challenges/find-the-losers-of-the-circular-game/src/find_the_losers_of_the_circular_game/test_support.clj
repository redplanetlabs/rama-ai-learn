(ns find-the-losers-of-the-circular-game.test-support
  "Shared test logic for the find-the-losers-of-the-circular-game challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'find-the-losers-of-the-circular-game.solution))
  (testing "circular-game-losers"
      (testing "case 0"
        (is (= [4 5] (solve-fn 5 2))))

      (testing "case 1"
        (is (= [2 3 4] (solve-fn 4 4))))))
