(ns fill-the-gaps.test-support
  "Shared test logic for the fill-the-gaps challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'fill-the-gaps.solution))
  (let [cases [{:input "4\n2 5 1 2\n" :expected "2 3 4 5 4 3 2 1 2"}
             {:input "6\n3 4 5 6 5 4\n" :expected "3 4 5 6 5 4"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
