(ns overall-winner.challenge-test
  "Verifies the agent's deframafn implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [overall-winner.test-support :as support]))

;; Tests verify the agent's solve deframafn.

(deftest challenge-test
  (testing "solve (agent implementation)"
    (support/test-solution
     (requiring-resolve 'overall-winner.solution/solve))))
