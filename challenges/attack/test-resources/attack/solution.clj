(ns attack.solution
  "Reference implementation for attack challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [clojure.string :as str]))

(deframafn solve [*input]
  (str/split (str/trim *input) #"\s+" :> *parts)
  (parse-long (nth *parts 0) :> *A)
  (parse-long (nth *parts 1) :> *B)
  (quot (+ *A *B -1) *B :> *result)
  (:> (str *result)))
