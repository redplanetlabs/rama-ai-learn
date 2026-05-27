(ns time-series-module-hard.performance-challenge-test
  "Runs performance tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [time-series-module-hard.performance-test-support :as support]))

(deftest performance-challenge-test
  (testing "TimeSeriesModuleHard (agent implementation, performance tests)"
    (support/test-module-performance
     (requiring-resolve 'time-series-module-hard.module/create-module))))
