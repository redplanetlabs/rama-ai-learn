(ns impartial-gift.test-support
  "Shared test logic for the impartial-gift challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'impartial-gift.solution))
  (let [cases [{:input "2 3 2\n3 10\n2 5 15\n" :expected "8"}
             {:input "3 3 0\n1 3 3\n6 2 7\n" :expected "-1"}
             {:input "1 1 1000000000000000000\n1000000000000000000\n1000000000000000000\n" :expected "2000000000000000000"}
             {:input "8 6 1\n2 5 6 5 2 1 7 9\n7 2 5 5 2 4\n" :expected "14"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
