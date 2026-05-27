(ns bitmask.solution
  "Reference implementation for bitmask challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [clojure.string :as str]))

;; Greedy approach: iterate bits MSB to LSB maintaining "tight" constraint.
;; When tight, we're still matching N's prefix. Either go loose (found a bit
;; where S forces us below N and we maximize remaining bits), backtrack to
;; the last '?' fallback position, or end up exactly at N.
(deframafn solve [*input]
  (str/trim *input :> *trimmed)
  (str/split-lines *trimmed :> *lines)
  (nth *lines 0 :> *s)
  (nth *lines 1 :> *n-str)
  (Long/parseLong *n-str :> *n)
  (count *s :> *len)
  ;; Build max-tail vector: max-tail[i] = max value achievable from position i onward.
  ;; max-tail[len] = 0; built right-to-left, stored as vector indexed by position.
  (loop<- [*i (dec *len) *mt (vector 0) :> *max-tail]
    (<<if (>= *i 0)
      (nth *s *i :> *ch)
      (- *len 1 *i :> *shift)
      (bit-shift-left 1 *shift :> *contrib)
      (<<if (= *ch \0)
        (identity 0 :> *c)
        (else>)
        (identity *contrib :> *c))
      (nth *mt 0 :> *head)
      (+ *head *c :> *new-head)
      (vec (cons *new-head *mt) :> *new-mt)
      (continue> (dec *i) *new-mt)
      (else>)
      (:> *mt)))
  (nth *max-tail 0 :> *max-val)
  ;; If N >= max of T, every element of T is <= N so return the maximum.
  (<<if (>= *n *max-val)
    (:> (str *max-val))
    (else>)
    ;; Greedy: tight throughout. Loop exits immediately on going loose or backtracking.
    ;; *fb = -1 means no fallback available (answer is -1 if backtrack needed).
    ;; *fb >= 0 is the precomputed result when using that fallback.
    (loop<- [*i 0 *val 0 *fb -1 :> *result]
      (<<if (< *i *len)
        (- *len 1 *i :> *shift)
        (bit-shift-right *n *shift :> *n-shifted)
        (bit-and *n-shifted 1 :> *n-bit)
        (nth *s *i :> *ch)
        (bit-shift-left 1 *shift :> *contrib)
        (<<if (= *ch \0)
          ;; Fixed 0 bit
          (<<if (= *n-bit 1)
            ;; S bit < N bit: go loose, maximize remaining positions
            (nth *max-tail (inc *i) :> *tail)
            (+ *val *tail :> *loose-val)
            (:> *loose-val)
            (else>)
            ;; Both 0: stay tight
            (continue> (inc *i) *val *fb))
          (else>)
          (<<if (= *ch \1)
            ;; Fixed 1 bit
            (<<if (= *n-bit 1)
              ;; Both 1: stay tight
              (+ *val *contrib :> *new-val)
              (continue> (inc *i) *new-val *fb)
              (else>)
              ;; S bit > N bit: exceeded N, backtrack to last fallback
              (:> *fb))
            (else>)
            ;; Wildcard '?'
            (<<if (= *n-bit 1)
              ;; Set to 1 to stay tight; record fallback (setting to 0 here, maximize rest)
              (nth *max-tail (inc *i) :> *tail)
              (+ *val *tail :> *fb-result)
              (+ *val *contrib :> *new-val)
              (continue> (inc *i) *new-val *fb-result)
              (else>)
              ;; N bit is 0: must set to 0 to stay tight
              (continue> (inc *i) *val *fb))))
        (else>)
        ;; Reached end while tight: val == N (valid since N is in T's range)
        (:> *val)))
    (<<if (= *result -1)
      (:> "-1")
      (else>)
      (:> (str *result)))))
