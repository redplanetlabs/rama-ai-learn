(ns neighboring-bitwise-xor.private-challenge-test
  "Runs hidden/private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [neighboring-bitwise-xor.private-test-support :as support]))

(deftest private-challenge-test
  (testing "does-valid-array-exist (agent implementation, private tests)"
    (support/test-solution
     (requiring-resolve 'neighboring-bitwise-xor.solution/does-valid-array-exist))))
