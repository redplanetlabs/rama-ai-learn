(ns number-of-adjacent-elements-with-the-same-color.test-support
  "Shared test logic for the number-of-adjacent-elements-with-the-same-color challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'number-of-adjacent-elements-with-the-same-color.solution))
  (testing "color-the-array"
      (testing "case 0"
        (is (= [0 1 1 0 2] (solve-fn 4 [[0 2] [1 2] [3 1] [1 1] [2 1]]))))

      (testing "case 1"
        (is (= [0] (solve-fn 1 [[0 100000]]))))))
