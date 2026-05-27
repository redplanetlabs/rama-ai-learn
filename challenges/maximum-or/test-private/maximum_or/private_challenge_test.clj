(ns maximum-or.private-challenge-test
  "Runs hidden/private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [maximum-or.private-test-support :as support]))

(deftest private-challenge-test
  (testing "maximum-or (agent implementation, private tests)"
    (support/test-solution
     (requiring-resolve 'maximum-or.solution/maximum-or))))
