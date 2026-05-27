(ns number-of-adjacent-elements-with-the-same-color.private-test-support
  "Shared test logic for the number-of-adjacent-elements-with-the-same-color private tests."
  (:require
   [clojure.test :refer [is testing]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [rama-challenges.harness :as harness]))

(defn- load-test-cases []
  (-> (io/resource "number_of_adjacent_elements_with_the_same_color/private_test_data.edn")
      slurp
      edn/read-string))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'number-of-adjacent-elements-with-the-same-color.solution))
  (testing "color-the-array"
    (doseq [{:keys [name args expected]} (load-test-cases)]
      (testing name
        (is (= expected (apply solve-fn args)))))))
