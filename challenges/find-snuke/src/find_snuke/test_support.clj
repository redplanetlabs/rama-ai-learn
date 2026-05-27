(ns find-snuke.test-support
  "Shared test logic for the find-snuke challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'find-snuke.solution))
  (let [cases [{:input "6 6\nvgxgpu\namkxks\nzhkbpp\nhykink\nesnuke\nzplvfj\n" :expected "5 2\n5 3\n5 4\n5 5\n5 6"}
             {:input "5 5\nezzzz\nzkzzz\nezuzs\nzzznz\nzzzzs\n" :expected "5 5\n4 4\n3 3\n2 2\n1 1"}
             {:input "10 10\nkseeusenuk\nusesenesnn\nkskekeeses\nnesnusnkkn\nsnenuuenke\nkukknkeuss\nneunnennue\nsknuessuku\nnksneekknk\nneeeuknenk\n" :expected "9 3\n8 3\n7 3\n6 3\n5 3"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
