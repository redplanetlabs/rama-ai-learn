(ns diagnose-stream-runtime-fail.test-support
  "Test support for the diagnose-stream-runtime-fail challenge."
  (:require [clojure.string :as str]
            [clojure.test :refer [is]]
            [com.rpl.challenges.cluster-support :as cluster-support]
            [com.rpl.rama :as r]))

(def module-name
  "The module name whose runtime failure the learner must diagnose."
  "com.rpl.challenges.stream-ack-fail/StreamAckFailModule")

(def topology-name
  "The deployed streaming topology expected to fail at runtime."
  "payments")

(def depot-name
  "The source depot for the failing topology."
  "*payments")

(def poison-pill-event
  "The record expected to trigger persistent runtime failure."
  {:user-id "u1" :amount "abc"})

(def ack-timeout-ms
  "Bounded wait used to probe Rama stream :ack completion."
  5000)

(def expected-diagnosis-substrings
  ["payments"
   "numberformatexception"
   "amount"
   "abc"
   "ack"])

(defn append-with-ack-timeout!
  "Appends `record` to `depot` with Rama stream :ack and returns either the
   ack result map or ::timeout if the ack does not complete within timeout-ms."
  [depot record timeout-ms]
  (deref (future (r/foreign-append! depot record :ack))
         timeout-ms
         ::timeout))

(defn verify-module-deployed
  "Asserts that the failing module is deployed on the cluster."
  []
  (cluster-support/assert-cluster-running!)
  (is (cluster-support/module-deployed? module-name)
      (str "Expected module " module-name " to be deployed")))

(defn verify-ack-times-out
  "Asserts that appending the poison-pill record with :ack does not complete
   successfully within the bounded timeout."
  []
  (cluster-support/assert-cluster-running!)
  (let [manager (cluster-support/create-cluster-manager)]
    (try
      (let [depot (r/foreign-depot manager module-name depot-name)
            result (append-with-ack-timeout! depot poison-pill-event ack-timeout-ms)]
        (is (= ::timeout result)
            (str "Expected poison-pill append with :ack to time out, got: "
                 (pr-str result))))
      (finally
        (.close manager)))))

(defn verify-diagnosis-written
  "Asserts that the learner wrote the diagnosis file with the key root-cause
   terms required by this challenge."
  []
  (let [path "implementations/diagnose-stream-runtime-fail/answer.md"
        content (slurp path)]
    (doseq [needle expected-diagnosis-substrings]
      (is (str/includes? (str/lower-case content) needle)
          (str "Expected diagnosis in " path " to mention: " needle)))))
