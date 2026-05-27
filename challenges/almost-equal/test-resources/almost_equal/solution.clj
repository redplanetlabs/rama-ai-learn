(ns almost-equal.solution
  "Reference implementation for almost-equal challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [clojure.string :as str]))

;;; Count differing character positions between s1 and s2, stopping early if > 1.
(deframafn count-diffs [*s1 *s2 *len]
  (loop<- [*i 0 *cnt 0 :> *result]
          (<<if (= *i *len)
                (:> *cnt)
                (else>)
                (<<if (> *cnt 1)
                      (:> *cnt)
                      (else>)
                      (nth *s1 *i :> *c1)
                      (nth *s2 *i :> *c2)
                      (<<if (= *c1 *c2)
                            (continue> (inc *i) *cnt)
                            (else>)
                            (continue> (inc *i) (inc *cnt))))))
  (:> *result))

;;; Iterative DFS over all permutations using an explicit stack.
;;; Each stack entry is [used-mask prev-idx placed-count] where
;;; prev-idx = -1 means no previous string has been placed yet.
(deframafn solve [*input]
  (str/split-lines (str/trim *input) :> *lines)
  (str/split (nth *lines 0) #"\s+" :> *parts)
  (parse-long (nth *parts 0) :> *n)
  (parse-long (nth *parts 1) :> *m)
  (vec (rest *lines) :> *strings)
  ;; DFS: stack starts with initial state [used=0 prev=-1 placed=0]
  (loop<- [*stack [[0 -1 0]] :> *found]
          (<<if (empty? *stack)
                (:> false)
                (else>)
                (peek *stack :> *frame)
                (pop *stack :> *rest-stack)
                (nth *frame 0 :> *used)
                (nth *frame 1 :> *prev-idx)
                (nth *frame 2 :> *placed)
                (<<if (= *placed *n)
                      (:> true)
                      (else>)
                      ;; Expand: try each unused string as next in sequence
                      (loop<- [*i 0 *new-stack *rest-stack :> *exp-stack]
                              (<<if (= *i *n)
                                    (:> *new-stack)
                                    (else>)
                                    (bit-and *used (bit-shift-left 1 *i) :> *bit)
                                    (<<if (= *bit 0)
                                          (<<if (= *prev-idx -1)
                                                ;; First string: always compatible
                                                (bit-or *used (bit-shift-left 1 *i) :> *u2)
                                                (conj *new-stack [*u2 *i (inc *placed)] :> *ns2)
                                                (continue> (inc *i) *ns2)
                                                (else>)
                                                ;; Must differ by exactly 1 character
                                                (nth *strings *prev-idx :> *prev-str)
                                                (nth *strings *i :> *s)
                                                (count-diffs *prev-str *s *m :> *diffs)
                                                (<<if (= *diffs 1)
                                                      (bit-or *used (bit-shift-left 1 *i) :> *u3)
                                                      (conj *new-stack [*u3 *i (inc *placed)] :> *ns3)
                                                      (continue> (inc *i) *ns3)
                                                      (else>)
                                                      (continue> (inc *i) *new-stack)))
                                          (else>)
                                          (continue> (inc *i) *new-stack))))
                      (continue> *exp-stack))))
  (<<if *found
        (:> "Yes")
        (else>)
        (:> "No")))
