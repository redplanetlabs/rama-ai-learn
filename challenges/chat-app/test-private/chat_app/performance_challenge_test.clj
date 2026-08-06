(ns chat-app.performance-challenge-test
  "Runs performance and fault-tolerance private tests against the agent
   implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [chat-app.performance-test-support :as support]))

(deftest write-volume-challenge-test
  (testing "chat-app: durable write volume of hot writes"
    (support/test-module-write-volume
     (requiring-resolve 'chat-app.module/create-module))))

(deftest read-costs-challenge-test
  (testing "chat-app: RocksDB read costs of the tough reads"
    (support/test-module-read-costs
     (requiring-resolve 'chat-app.module/create-module))))

(deftest fault-tolerance-challenge-test
  (testing "chat-app: durable state survives module update; presence resets"
    (support/test-module-fault-tolerance
     (requiring-resolve 'chat-app.module/create-module))))
