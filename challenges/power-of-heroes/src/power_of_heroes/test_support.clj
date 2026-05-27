(ns power-of-heroes.test-support
  "Shared test logic for the power-of-heroes challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'power-of-heroes.solution))
  (testing "sum-of-power"
      (testing "case 0"
        (is (= 141 (solve-fn [2 1 4]))))

      (testing "case 1"
        (is (= 7 (solve-fn [1 1 1]))))))
