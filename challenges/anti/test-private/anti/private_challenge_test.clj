(ns anti.private-challenge-test
  "Runs hidden/private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [anti.private-test-support :as support]))

(deftest private-challenge-test
  (testing "solve (agent implementation, private tests)"
    (support/test-solution
     (requiring-resolve 'anti.solution/solve))))
