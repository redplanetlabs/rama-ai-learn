(ns auction-module.performance-challenge-test
  "Runs performance tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [auction-module.performance-test-support :as support]))

(deftest performance-challenge-test
  (testing "AuctionModule (agent implementation, performance tests)"
    (support/test-module-performance
     (requiring-resolve 'auction-module.module/create-module))))
