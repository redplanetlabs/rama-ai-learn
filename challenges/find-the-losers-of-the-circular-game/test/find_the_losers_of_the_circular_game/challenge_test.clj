(ns find-the-losers-of-the-circular-game.challenge-test
  "Verifies the agent's deframafn implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [find-the-losers-of-the-circular-game.test-support :as support]))

;; Tests verify the agent's circular-game-losers deframafn.

(deftest challenge-test
  (testing "circular-game-losers (agent implementation)"
    (support/test-solution
     (requiring-resolve 'find-the-losers-of-the-circular-game.solution/circular-game-losers))))
