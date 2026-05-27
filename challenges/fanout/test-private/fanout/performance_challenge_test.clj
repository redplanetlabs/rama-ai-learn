(ns fanout.performance-challenge-test
  "Runs performance tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [fanout.performance-test-support :as support]))

(deftest performance-challenge-test
  (testing "Fanout (agent implementation, performance tests)"
    (support/test-module-performance
     (requiring-resolve 'fanout.module/create-module))))
