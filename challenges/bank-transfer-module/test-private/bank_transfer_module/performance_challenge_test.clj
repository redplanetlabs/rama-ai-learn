(ns bank-transfer-module.performance-challenge-test
  "Runs performance tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [bank-transfer-module.performance-test-support :as support]))

(deftest performance-challenge-test
  (testing "BankTransferModule (agent implementation, performance tests)"
    (support/test-module-performance
     (requiring-resolve 'bank-transfer-module.module/create-module))))
