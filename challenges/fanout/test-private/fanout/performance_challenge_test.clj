(ns fanout.performance-challenge-test
  "Runs performance and fault-tolerance tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [fanout.performance-test-support :as support]))

(deftest performance-challenge-test
  (testing "Fanout (agent implementation, performance tests)"
    (support/test-module-performance
     (requiring-resolve 'fanout.module/create-module))))

(deftest fault-tolerance-challenge-test
  (testing "Fanout (agent implementation, fault-tolerance tests)"
    (support/test-module-fault-tolerance
     (requiring-resolve 'fanout.module/create-module))))
