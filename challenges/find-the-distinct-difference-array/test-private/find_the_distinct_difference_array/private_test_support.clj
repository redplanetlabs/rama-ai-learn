(ns find-the-distinct-difference-array.private-test-support
  "Shared test logic for the find-the-distinct-difference-array.private-test-support namespace."
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
        (is (= [1] (solve-fn [3]))))

      (testing "case 1"
        (is (= [1] (solve-fn [9]))))

      (testing "case 2"
        (is (= [1] (solve-fn [6]))))

      (testing "case 3"
        (is (= [1] (solve-fn [4]))))

      (testing "case 4"
        (is (= [1] (solve-fn [10]))))

      (testing "case 5"
        (is (= [1] (solve-fn [22]))))

      (testing "case 6"
        (is (= [0 2] (solve-fn [3 5]))))

      (testing "case 7"
        (is (= [-1 1 3] (solve-fn [7 3 2]))))

      (testing "case 8"
        (is (= [-33 -31 -30 -29 -28 -26 -25 -23 -21 -19 -17 -15 -13 -12 -10 -9 -7 -6 -6 -6 -4 -3 -1 -1 0 2 4 5 5 6 8 9 11 13 15 15 17 18 19 20 22 24 26 27 27 28 30 31 33 34] (solve-fn [32 6 45 12 26 20 43 39 35 29 14 48 18 50 46 40 4 5 32 43 38 50 37 40 42 7 17 32 43 41 11 5 27 9 34 40 28 26 40 45 36 22 21 42 43 43 13 41 8 12]))))

      (testing "case 9"
        (is (= [-35 -34 -32 -31 -29 -28 -27 -26 -24 -22 -20 -18 -16 -14 -13 -13 -12 -11 -10 -9 -8 -6 -5 -4 -2 -1 0 2 4 5 5 7 9 10 11 13 14 16 17 19 21 22 24 26 27 29 31 32 34 36] (solve-fn [33 50 2 5 28 19 39 8 16 44 27 14 34 29 40 39 39 15 40 5 10 45 22 19 48 21 23 46 17 22 21 1 11 33 10 36 8 49 15 30 47 21 9 37 23 6 43 50 32 35]))))

      (testing "case 10"
        (is (= [-31 -29 -28 -26 -25 -23 -22 -20 -18 -17 -16 -15 -14 -12 -11 -11 -10 -9 -9 -8 -7 -6 -5 -3 -2 0 1 1 3 4 6 8 10 12 14 15 16 17 19 20 22 23 25 25 27 28 30 31 32 33] (solve-fn [47 14 8 13 32 35 34 39 20 38 40 26 29 11 45 45 8 33 38 33 40 12 38 24 23 27 23 32 15 48 18 36 21 1 19 7 34 32 46 45 49 48 2 26 10 7 44 12 29 26]))))

      (testing "case 11"
        (is (= [-48 -46 -44 -42 -40 -38 -36 -34 -32 -30 -28 -26 -24 -22 -20 -18 -16 -14 -12 -10 -8 -6 -4 -2 0 2 4 6 8 10 12 14 16 18 20 22 24 26 28 30 32 34 36 38 40 42 44 46 48 50] (solve-fn [1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50]))))))
