(ns diagnose-stream-runtime-fail.harness-test
  "Validates the diagnose-stream-runtime-fail harness by reproducing the
   failing runtime setup and confirming the reference diagnosis passes."
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [diagnose-stream-runtime-fail.test-support :as support]))

(defn- run-script! [path]
  (let [result (shell/sh "bash" path)]
    (is (zero? (:exit result))
        (str "Script failed: " path "\n" (:err result) (:out result)))))

(deftest harness-detects-runtime-failure-test
  (testing "harness-detects-runtime-failure"
    (try
      (run-script! "test-resources/diagnose_stream_runtime_fail/reference_solution.sh")
      (doto (java.io.File. "implementations/diagnose-stream-runtime-fail") .mkdirs)
      (spit "implementations/diagnose-stream-runtime-fail/answer.md"
            (str "The module com.rpl.challenges.stream-ack-fail/StreamAckFailModule is deployed, "
                 "but the streaming topology payments is repeatedly failing at runtime with a "
                 "NumberFormatException. It tries to parse field :amount as a number, but the "
                 "poison-pill event uses the non-numeric value \"abc\", so foreign-append! "
                 "with :ack does not return an ack."))
      (testing "module is deployed"
        (support/verify-module-deployed))
      (testing "poison-pill append reproduces :ack timeout"
        (support/verify-ack-times-out))
      (testing "reference diagnosis explains the root cause"
        (support/verify-diagnosis-written))
      (finally
        (run-script! "test-resources/diagnose_stream_runtime_fail/teardown.sh")))))
