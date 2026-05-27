(ns atcoder-cards.test-support
  "Shared test logic for the atcoder-cards challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'atcoder-cards.solution))
  (let [cases [{:input "ch@ku@ai\nchoku@@i\n" :expected "Yes"}
             {:input "ch@kud@i\nakidu@ho\n" :expected "Yes"}
             {:input "aoki\n@ok@\n" :expected "No"}
             {:input "aa\nbb\n" :expected "No"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
