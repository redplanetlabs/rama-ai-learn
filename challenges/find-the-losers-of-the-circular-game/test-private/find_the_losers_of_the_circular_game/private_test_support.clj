(ns find-the-losers-of-the-circular-game.private-test-support
  "Shared test logic for the find-the-losers-of-the-circular-game.private-test-support namespace."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'find-the-losers-of-the-circular-game.solution))
  (testing "circular-game-losers"
      (testing "case 0"
        (is (= [] (solve-fn 4 3))))

      (testing "case 1"
        (is (= [] (solve-fn 2 1))))

      (testing "case 2"
        (is (= [] (solve-fn 1 1))))

      (testing "case 3"
        (is (= [] (solve-fn 4 1))))

      (testing "case 4"
        (is (= [3] (solve-fn 3 1))))

      (testing "case 5"
        (is (= [2 3] (solve-fn 3 3))))

      (testing "case 6"
        (is (= [2 3 4 6] (solve-fn 6 4))))

      (testing "case 7"
        (is (= [2 3 5 6 7 8 9 11 12 13 14 15 16 17 18 19 20 21 23 24 26 27 29 30 31 32 33] (solve-fn 33 9))))

      (testing "case 8"
        (is (= [3 5 8 9 10 12 13 14 15 18 19 20 21 23 24 25 26 27 28 30 31 32 33 34 35 36 38 39 40 41 42 43 44 45 47 48 49 50] (solve-fn 50 1))))

      (testing "case 9"
        (is (= [2 3 4 5 6 7 9 10 11 12 13 14 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 37 38 39 40 41 42 43 44 45 46 47 48 49] (solve-fn 49 35))))

      (testing "case 10"
        (is (= [2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50] (solve-fn 50 25))))

      (testing "case 11"
        (is (= [2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50] (solve-fn 50 50))))))
