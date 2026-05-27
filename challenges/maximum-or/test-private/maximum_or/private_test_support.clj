(ns maximum-or.private-test-support
  "Shared test logic for the maximum-or private tests."
  (:require
   [clojure.test :refer [is testing]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [rama-challenges.harness :as harness]))

(defn- load-test-cases []
  (-> (io/resource "maximum_or/private_test_data.edn")
      slurp
      edn/read-string))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'maximum-or.solution))
  (testing "maximum-or"
    (doseq [{:keys [name args expected]} (load-test-cases)]
      (testing name
        (is (= expected (apply solve-fn args)))))))
