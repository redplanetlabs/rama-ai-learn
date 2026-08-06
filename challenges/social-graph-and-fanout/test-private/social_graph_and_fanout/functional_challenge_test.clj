(ns social-graph-and-fanout.functional-challenge-test
  "Runs functional private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [social-graph-and-fanout.functional-test-support :as support]))

(deftest social-graph-functional-challenge-test
  (testing "Social graph and fanout (agent implementation, social-graph functional tests)"
    (support/test-module-social-graph-functional
     (requiring-resolve 'social-graph-and-fanout.module/create-module))))

(deftest fanout-functional-challenge-test
  (testing "Social graph and fanout (agent implementation, fanout functional tests)"
    (support/test-module-fanout-functional
     (requiring-resolve 'social-graph-and-fanout.module/create-module))))
