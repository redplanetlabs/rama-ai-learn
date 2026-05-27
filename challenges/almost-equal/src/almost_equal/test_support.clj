(ns almost-equal.test-support
  "Shared test logic for the almost-equal challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'almost-equal.solution))
  (let [cases [{:input "4 4\nbbed\nabcd\nabed\nfbed\n" :expected "Yes"}
             {:input "2 5\nabcde\nabced\n" :expected "No"}
             {:input "8 4\nfast\nface\ncast\nrace\nfact\nrice\nnice\ncase\n" :expected "Yes"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
