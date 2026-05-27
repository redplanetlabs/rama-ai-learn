(ns bitmask.test-support
  "Shared test logic for the bitmask challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'bitmask.solution))
  (let [cases [{:input "?0?\n2\n" :expected "1"}
             {:input "101\n4\n" :expected "-1"}
             {:input "?0?\n1000000000000000000\n" :expected "5"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
