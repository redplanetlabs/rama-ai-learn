(ns maximum-or.test-support
  "Shared test logic for the maximum-or challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'maximum-or.solution))
  (testing "maximum-or"
      (testing "case 0"
        (is (= 30 (solve-fn [12 9] 1))))

      (testing "case 1"
        (is (= 35 (solve-fn [8 1 2] 2))))))
