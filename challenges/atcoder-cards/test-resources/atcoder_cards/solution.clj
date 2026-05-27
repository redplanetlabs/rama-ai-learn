(ns atcoder-cards.solution
  "Reference implementation for atcoder-cards challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [clojure.string :as str]))

(deframafn solve [*input]
  (str/trim *input :> *trimmed)
  (str/split-lines *trimmed :> *lines)
  (nth *lines 0 :> *s)
  (nth *lines 1 :> *t)
  (count *s :> *n)
  ;; Count chars in S using an explicit loop
  (loop<- [*i 0 *fs {} :> *freq-s]
    (<<if (< *i *n)
      (nth *s *i :> *ch)
      (get *fs *ch 0 :> *cur)
      (inc *cur :> *new-cur)
      (assoc *fs *ch *new-cur :> *new-fs)
      (continue> (inc *i) *new-fs)
      (else>)
      (:> *fs)))
  ;; Count chars in T using an explicit loop
  (loop<- [*i 0 *ft {} :> *freq-t]
    (<<if (< *i *n)
      (nth *t *i :> *ch)
      (get *ft *ch 0 :> *cur)
      (inc *cur :> *new-cur)
      (assoc *ft *ch *new-cur :> *new-ft)
      (continue> (inc *i) *new-ft)
      (else>)
      (:> *ft)))
  ;; Iterate over all possible chars to check validity.
  ;; For non-atcoder non-@ chars: counts must match.
  ;; For atcoder chars: track how many @s each row needs.
  (identity "abcdefghijklmnopqrstuvwxyz@" :> *all-chars-str)
  (identity #{\a \t \c \o \d \e \r} :> *atcoder)
  (count *all-chars-str :> *n-chars)
  (loop<- [*i 0 *s-needs 0 *t-needs 0 *valid true :> *sv *sn *tn]
    (<<if (< *i *n-chars)
      (nth *all-chars-str *i :> *ch)
      (get *freq-s *ch 0 :> *cs)
      (get *freq-t *ch 0 :> *ct)
      (<<if (= *ch \@)
        ;; @ counts are checked at the end
        (continue> (inc *i) *s-needs *t-needs *valid)
        (else>)
        (<<if (contains? *atcoder *ch)
          ;; atcoder char: diff > 0 means T needs more (uses T's @s)
          (- *cs *ct :> *diff)
          (<<if (> *diff 0)
            (+ *t-needs *diff :> *new-t-needs)
            (continue> (inc *i) *s-needs *new-t-needs *valid)
            (else>)
            (- *ct *cs :> *neg-diff)
            (+ *s-needs *neg-diff :> *new-s-needs)
            (continue> (inc *i) *new-s-needs *t-needs *valid))
          (else>)
          ;; non-atcoder non-@ char: counts must be equal in both rows
          (<<if (not= *cs *ct)
            (continue> (inc *i) *s-needs *t-needs false)
            (else>)
            (continue> (inc *i) *s-needs *t-needs *valid))))
      (else>)
      (:> *valid *s-needs *t-needs)))
  (get *freq-s \@ 0 :> *s-ats)
  (get *freq-t \@ 0 :> *t-ats)
  ;; Use nested <<if instead of (and ...) to avoid let* expansion issues
  (<<if *sv
    (<<if (<= *sn *s-ats)
      (<<if (<= *tn *t-ats)
        (:> "Yes")
        (else>)
        (:> "No"))
      (else>)
      (:> "No"))
    (else>)
    (:> "No")))
