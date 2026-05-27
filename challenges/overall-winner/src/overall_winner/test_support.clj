(ns overall-winner.test-support
  "Shared test logic for the overall-winner challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'overall-winner.solution))
  (let [cases [{:input "5\nTTAAT\n" :expected "T"}
             {:input "6\nATTATA\n" :expected "T"}
             {:input "1\nA\n" :expected "A"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
