(ns sum-in-a-matrix.private-test-support
  "Shared test logic for the sum-in-a-matrix private tests."
  (:require
   [clojure.test :refer [is testing]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [rama-challenges.harness :as harness]))

(defn- load-test-cases []
  (-> (io/resource "sum_in_a_matrix/private_test_data.edn")
      slurp
      edn/read-string))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'sum-in-a-matrix.solution))
  (testing "matrix-sum"
    (doseq [{:keys [name args expected]} (load-test-cases)]
      (testing name
        (is (= expected (apply solve-fn args)))))))
