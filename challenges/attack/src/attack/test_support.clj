(ns attack.test-support
  "Shared test logic for the attack challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'attack.solution))
  (let [cases [{:input "7 3\n" :expected "3"}
             {:input "123456789123456789 987654321\n" :expected "124999999"}
             {:input "999999999999999998 2\n" :expected "499999999999999999"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
