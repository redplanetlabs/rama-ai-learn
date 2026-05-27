(ns power-of-heroes.challenge-test
  "Verifies the agent's deframafn implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [power-of-heroes.test-support :as support]))

;; Tests verify the agent's sum-of-power deframafn.

(deftest challenge-test
  (testing "sum-of-power (agent implementation)"
    (support/test-solution
     (requiring-resolve 'power-of-heroes.solution/sum-of-power))))
