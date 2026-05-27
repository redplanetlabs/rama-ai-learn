(ns sum-in-a-matrix.test-support
  "Shared test logic for the sum-in-a-matrix challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'sum-in-a-matrix.solution))
  (testing "matrix-sum"
      (testing "case 0"
        (is (= 15 (solve-fn [[7 2 1] [6 4 2] [6 5 3] [3 2 1]]))))

      (testing "case 1"
        (is (= 1 (solve-fn [[1]]))))))
