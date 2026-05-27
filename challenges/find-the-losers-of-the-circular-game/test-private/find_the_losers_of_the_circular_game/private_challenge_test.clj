(ns find-the-losers-of-the-circular-game.private-challenge-test
  "Runs hidden/private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [find-the-losers-of-the-circular-game.private-test-support :as support]))

(deftest private-challenge-test
  (testing "circular-game-losers (agent implementation, private tests)"
    (support/test-solution
     (requiring-resolve 'find-the-losers-of-the-circular-game.solution/circular-game-losers))))
