(ns anti.test-support
  "Shared test logic for the anti challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'anti.solution))
  (let [cases [{:input "DD??S\n" :expected "676"}
             {:input "????????????????????????????????????????\n" :expected "858572093"}
             {:input "?D??S\n" :expected "136604"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
