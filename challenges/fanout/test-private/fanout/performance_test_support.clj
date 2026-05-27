(ns fanout.performance-test-support
  "Performance tests for fanout."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [rama-challenges.shared :as shared]
   [fanout.protocol :as p]))

(defn test-module-performance
  [create-module-fn]
  ;; TODO
  )
