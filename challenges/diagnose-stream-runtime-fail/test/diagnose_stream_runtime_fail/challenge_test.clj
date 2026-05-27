(ns diagnose-stream-runtime-fail.challenge-test
  "Post-condition verification for the diagnose-stream-runtime-fail challenge."
  (:require [clojure.test :refer [deftest testing]]
            [diagnose-stream-runtime-fail.test-support :as support]))

(deftest diagnose-stream-runtime-fail-challenge-test
  (testing "diagnose-stream-runtime-fail"
    (testing "failing module is deployed"
      (support/verify-module-deployed))
    (testing "poison-pill append with :ack reproduces the failure symptom"
      (support/verify-ack-times-out))
    (testing "diagnosis writeup explains the runtime root cause"
      (support/verify-diagnosis-written))))
