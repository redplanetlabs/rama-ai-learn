(ns auction-module.functional-challenge-test
  "Runs functional private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [auction-module.functional-test-support :as support]))

(deftest functional-challenge-test
  (testing "AuctionModule (agent implementation, functional tests)"
    (support/test-module-functional
     (requiring-resolve 'auction-module.module/create-module))))
