(ns neighboring-bitwise-xor.challenge-test
  "Verifies the agent's deframafn implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [neighboring-bitwise-xor.test-support :as support]))

;; Tests verify the agent's does-valid-array-exist deframafn.

(deftest challenge-test
  (testing "does-valid-array-exist (agent implementation)"
    (support/test-solution
     (requiring-resolve 'neighboring-bitwise-xor.solution/does-valid-array-exist))))
