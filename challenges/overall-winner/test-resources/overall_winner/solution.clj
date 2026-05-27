(ns overall-winner.solution
  "Reference implementation for overall-winner challenge."
  (:require [clojure.string :as str]
            [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

(deframafn solve [*input]
  ;; Parse: skip first line, get the game string from second line
  (str/split-lines (str/trim *input) :> *lines)
  (nth *lines 1 :> *s)
  (count *s :> *n)
  ;; Count total T and A wins
  (loop<- [*i 0 *t-count 0 *a-count 0 :> *tc *ac]
    (<<if (< *i *n)
      (nth *s *i :> *ch)
      (<<if (= *ch \T)
        (continue> (inc *i) (inc *t-count) *a-count)
        (else>)
        (continue> (inc *i) *t-count (inc *a-count)))
      (else>)
      (:> *t-count *a-count)))
  ;; Determine winner
  (<<if (> *tc *ac)
    (:> "T")
    (else>)
    (<<if (< *tc *ac)
      (:> "A")
      (else>)
      ;; Tied: find who reached the target count first
      (loop<- [*i 0 *t 0 *a 0 :> *result]
        (<<if (< *i *n)
          (nth *s *i :> *ch)
          (<<if (= *ch \T)
            (inc *t :> *new-t)
            (<<if (= *new-t *tc)
              (:> "T")
              (else>)
              (continue> (inc *i) *new-t *a))
            (else>)
            (inc *a :> *new-a)
            (<<if (= *new-a *ac)
              (:> "A")
              (else>)
              (continue> (inc *i) *t *new-a)))
          (else>)
          (:> "T")))
      (:> *result))))
