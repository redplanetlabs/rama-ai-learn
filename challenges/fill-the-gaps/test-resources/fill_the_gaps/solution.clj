(ns fill-the-gaps.solution
  "Reference implementation for fill-the-gaps challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [clojure.string :as str]))

(deframafn solve [*input]
  ;; Parse input
  (str/split-lines (str/trim *input) :> *lines)
  (nth *lines 1 :> *nums-line)
  (str/split *nums-line #" " :> *parts)
  (count *parts :> *n)
  ;; Parse all number strings into a vector of ints
  (loop<- [*i 0 *nums [] :> *parsed]
    (<<if (< *i *n)
      (nth *parts *i :> *s)
      (Integer/parseInt *s :> *v)
      (conj *nums *v :> *new-nums)
      (continue> (inc *i) *new-nums)
      (else>)
      (:> *nums)))
  ;; Build result by filling gaps between adjacent elements
  (nth *parsed 0 :> *first)
  (loop<- [*i 1 *result [*first] :> *out]
    (<<if (< *i *n)
      (nth *parsed *i :> *cur)
      (nth *parsed (dec *i) :> *prev)
      ;; Determine step direction
      (<<if (< *prev *cur)
        (identity 1 :> *step)
        (else>)
        (identity -1 :> *step))
      ;; Fill from prev+step to cur (inclusive)
      (+ *prev *step :> *start)
      (loop<- [*j *start *r *result :> *r2]
        (<<if (not= *j (+ *cur *step))
          (conj *r *j :> *nr)
          (+ *j *step :> *next-j)
          (continue> *next-j *nr)
          (else>)
          (:> *r)))
      (continue> (inc *i) *r2)
      (else>)
      (:> *result)))
  (str/join " " *out :> *answer)
  (:> *answer))
