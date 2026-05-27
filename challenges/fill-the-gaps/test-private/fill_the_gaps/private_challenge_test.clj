(ns fill-the-gaps.private-challenge-test
  "Runs hidden/private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [fill-the-gaps.private-test-support :as support]))

(deftest private-challenge-test
  (testing "solve (agent implementation, private tests)"
    (support/test-solution
     (requiring-resolve 'fill-the-gaps.solution/solve))))
