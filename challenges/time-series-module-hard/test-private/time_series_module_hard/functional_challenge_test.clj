(ns time-series-module-hard.functional-challenge-test
  "Runs functional private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [time-series-module-hard.functional-test-support :as support]))

(deftest functional-challenge-test
  (testing "TimeSeriesModuleHard (agent implementation, functional tests)"
    (support/test-module-functional
     (requiring-resolve 'time-series-module-hard.module/create-module))))
