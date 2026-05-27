(ns number-of-senior-citizens.private-challenge-test
  "Runs hidden/private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [number-of-senior-citizens.private-test-support :as support]))

(deftest private-challenge-test
  (testing "count-seniors (agent implementation, private tests)"
    (support/test-solution
     (requiring-resolve 'number-of-senior-citizens.solution/count-seniors))))
