(ns word-count.functional-challenge-test
  "Runs functional private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [word-count.functional-test-support :as support]))

(deftest functional-challenge-test
  (testing "Word count (agent implementation, functional tests)"
    (support/test-module-functional
     (requiring-resolve 'word-count.module/create-module))))
