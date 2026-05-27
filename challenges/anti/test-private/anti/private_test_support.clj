(ns anti.private-test-support
  "Shared test logic for the anti private tests."
  (:require
   [clojure.test :refer [is testing]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [rama-challenges.harness :as harness]))

(defn- load-test-cases []
  (-> (io/resource "anti/private_test_data.edn")
      slurp
      edn/read-string))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'anti.solution))
  (let [cases (load-test-cases)]
    (doseq [{:keys [input expected]} cases]
      (testing (str "input: " (subs input 0 (min 40 (count input))) "...")
        (is (= expected (solve-fn input)))))))
