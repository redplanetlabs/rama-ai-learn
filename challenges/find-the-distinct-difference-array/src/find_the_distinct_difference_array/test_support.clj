(ns find-the-distinct-difference-array.test-support
  "Shared test logic for the find-the-distinct-difference-array challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'find-the-distinct-difference-array.solution))
  (testing "distinct-difference-array"
      (testing "case 0"
        (is (= [-3 -1 1 3 5] (solve-fn [1 2 3 4 5]))))

      (testing "case 1"
        (is (= [-2 -1 0 2 3] (solve-fn [3 2 3 4 2]))))))
