(ns social-graph-and-fanout.performance-challenge-test
  "Runs performance and fault-tolerance tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [social-graph-and-fanout.performance-test-support :as support]))

(deftest performance-challenge-test
  (testing "Social graph and fanout (agent implementation, performance tests)"
    (support/test-module-performance
     (requiring-resolve 'social-graph-and-fanout.module/create-module))))

(deftest fault-tolerance-challenge-test
  (testing "Social graph and fanout (agent implementation, fault-tolerance tests)"
    (support/test-module-fault-tolerance
     (requiring-resolve 'social-graph-and-fanout.module/create-module))))
