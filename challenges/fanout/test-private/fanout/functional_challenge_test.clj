(ns fanout.functional-challenge-test
  "Runs functional private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [fanout.functional-test-support :as support]))

(deftest functional-challenge-test
  (testing "Fanout (agent implementation, functional tests)"
    (support/test-module-functional
     (requiring-resolve 'fanout.module/create-module))))
