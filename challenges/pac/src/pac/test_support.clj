(ns pac.test-support
  "Shared test logic for the pac challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'pac.solution))
  (let [cases [{:input "3 3 5\nS.G\no#o\n.#.\n" :expected "1"}
             {:input "3 3 1\nS.G\n.#o\no#.\n" :expected "-1"}
             {:input "5 10 2000000\nS.o..ooo..\n..o..o.o..\n..o..ooo..\n..o..o.o..\n..o..ooo.G\n" :expected "18"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
