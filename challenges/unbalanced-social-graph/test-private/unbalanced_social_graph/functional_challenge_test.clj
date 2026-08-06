(ns unbalanced-social-graph.functional-challenge-test
  "Runs functional private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [unbalanced-social-graph.functional-test-support :as support]))

(deftest functional-challenge-test
  (testing "Unbalanced social graph (agent implementation, functional tests)"
    (support/test-module-functional
     (requiring-resolve 'unbalanced-social-graph.module/create-module))))
