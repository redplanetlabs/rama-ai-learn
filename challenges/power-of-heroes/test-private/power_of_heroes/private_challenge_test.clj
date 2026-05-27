(ns power-of-heroes.private-challenge-test
  "Runs hidden/private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [power-of-heroes.private-test-support :as support]))

(deftest private-challenge-test
  (testing "sum-of-power (agent implementation, private tests)"
    (support/test-solution
     (requiring-resolve 'power-of-heroes.solution/sum-of-power))))
