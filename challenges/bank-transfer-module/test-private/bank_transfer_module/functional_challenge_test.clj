(ns bank-transfer-module.functional-challenge-test
  "Runs functional private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [bank-transfer-module.functional-test-support :as support]))

(deftest functional-challenge-test
  (testing "BankTransferModule (agent implementation, functional tests)"
    (support/test-module-functional
     (requiring-resolve 'bank-transfer-module.module/create-module))))
