(ns neighboring-bitwise-xor.test-support
  "Shared test logic for the neighboring-bitwise-xor challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'neighboring-bitwise-xor.solution))
  (testing "does-valid-array-exist"
      (testing "case 0"
        (is (= true (solve-fn [1 1 0]))))

      (testing "case 1"
        (is (= true (solve-fn [1 1]))))

      (testing "case 2"
        (is (= false (solve-fn [1 0]))))))
