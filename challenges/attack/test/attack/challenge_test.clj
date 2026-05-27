(ns attack.challenge-test
  "Verifies the agent's deframafn implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [attack.test-support :as support]))

;; Tests verify the agent's solve deframafn.

(deftest challenge-test
  (testing "solve (agent implementation)"
    (support/test-solution
     (requiring-resolve 'attack.solution/solve))))
