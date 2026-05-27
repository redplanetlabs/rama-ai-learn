(ns atcoder-cards.private-test-support
  "Shared test logic for the atcoder-cards private tests."
  (:require
   [clojure.test :refer [is testing]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [rama-challenges.harness :as harness]))

(defn- load-test-cases []
  (-> (io/resource "atcoder_cards/private_test_data.edn")
      slurp
      edn/read-string))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'atcoder-cards.solution))
  (let [cases (load-test-cases)]
    (doseq [{:keys [input expected]} cases]
      (testing (str "input: " (subs input 0 (min 40 (count input))) "...")
        (is (= expected (solve-fn input)))))))
