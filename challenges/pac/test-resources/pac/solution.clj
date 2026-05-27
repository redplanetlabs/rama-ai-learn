(ns pac.solution
  "Reference implementation for pac challenge.
  BFS between key nodes (S, G, candies) + bitmask DP for max candy collection."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [clojure.string :as str]))

;;; Parsing

(deframafn parse-input [*input]
  (str/split-lines *input :> *lines)
  (str/split (nth *lines 0) #" " :> *parts)
  (parse-long (nth *parts 0) :> *H)
  (parse-long (nth *parts 1) :> *W)
  (parse-long (nth *parts 2) :> *T)
  (loop<- [*r 0 *grid [] :> *grid-done]
    (<<if (< *r *H)
      (nth *lines (inc *r) :> *row-str)
      (conj *grid *row-str :> *g2)
      (continue> (inc *r) *g2)
      (else>)
      (:> *grid)))
  (:> [*grid-done *H *W *T]))

;;; Grid scanning

;; Returns [key-nodes num-candies] where key-nodes is [[sr sc] [gr gc] [c0r c0c] ...]
(deframafn find-key-nodes [*grid *H *W]
  (loop<- [*r 0 *start [-1 -1] *goal [-1 -1] *candies [] :> *s *g *cs]
    (<<if (< *r *H)
      (loop<- [*c 0 *st *start *gl *goal *cn *candies :> *s2 *g2 *c2]
        (<<if (< *c *W)
          (nth *grid *r :> *row-s)
          (nth *row-s *c :> *ch)
          (<<if (= *ch \S)
            (continue> (inc *c) [*r *c] *gl *cn)
            (else>)
            (<<if (= *ch \G)
              (continue> (inc *c) *st [*r *c] *cn)
              (else>)
              (<<if (= *ch \o)
                (conj *cn [*r *c] :> *cn2)
                (continue> (inc *c) *st *gl *cn2)
                (else>)
                (continue> (inc *c) *st *gl *cn))))
          (else>)
          (:> *st *gl *cn)))
      (continue> (inc *r) *s2 *g2 *c2)
      (else>)
      (:> *start *goal *candies)))
  ;; Build key-nodes: [S, G, c0, c1, ...]
  (loop<- [*i 0 *kn [*s *g] :> *key-nodes]
    (<<if (< *i (count *cs))
      (conj *kn (nth *cs *i) :> *kn2)
      (continue> (inc *i) *kn2)
      (else>)
      (:> *kn)))
  (count *cs :> *num-candies)
  (:> [*key-nodes *num-candies]))

;;; BFS

;; BFS from a single source. Returns flat distance vector H*W (-1 = unreachable).
(deframafn bfs [*grid *H *W *start-row *start-col]
  (* *H *W :> *grid-size)
  (vec (repeat *grid-size -1) :> *init-dist)
  (+ (* *start-row *W) *start-col :> *src-idx)
  (assoc *init-dist *src-idx 0 :> *dist-0)
  (loop<- [*queue [[*start-row *start-col]] *qi 0 *dist *dist-0 :> *bfs-dist]
    (<<if (< *qi (count *queue))
      (nth *queue *qi :> *cur)
      (nth *cur 0 :> *cr)
      (nth *cur 1 :> *cc)
      (+ (* *cr *W) *cc :> *ci)
      (nth *dist *ci :> *cd)
      ;; Try 4 directions
      (loop<- [*di 0 *q *queue *d *dist :> *q-out *d-out]
        (<<if (< *di 4)
          (<<if (= *di 0)
            (dec *cr :> *nr)
            (identity *cc :> *nc)
            (else>)
            (<<if (= *di 1)
              (inc *cr :> *nr)
              (identity *cc :> *nc)
              (else>)
              (<<if (= *di 2)
                (identity *cr :> *nr)
                (dec *cc :> *nc)
                (else>)
                (identity *cr :> *nr)
                (inc *cc :> *nc))))
          (and> (>= *nr 0) (< *nr *H) (>= *nc 0) (< *nc *W) :> *in-bounds)
          (<<if *in-bounds
            (nth *grid *nr :> *nr-row)
            (nth *nr-row *nc :> *nch)
            (<<if (not= *nch \#)
              (+ (* *nr *W) *nc :> *ni)
              (nth *d *ni :> *nd)
              (<<if (= *nd -1)
                (assoc *d *ni (inc *cd) :> *d2)
                (conj *q [*nr *nc] :> *q2)
                (continue> (inc *di) *q2 *d2)
                (else>)
                (continue> (inc *di) *q *d))
              (else>)
              (continue> (inc *di) *q *d))
            (else>)
            (continue> (inc *di) *q *d))
          (else>)
          (:> *q *d)))
      (continue> *q-out (inc *qi) *d-out)
      (else>)
      (:> *dist)))
  (:> *bfs-dist))

;; BFS from each key node. Returns flat dist-matrix N*N, entry [i*N+j] = shortest i->j.
(deframafn build-dist-matrix [*grid *H *W *key-nodes]
  (count *key-nodes :> *num-keys)
  (loop<- [*ki 0 *dist-matrix (vec (repeat (* *num-keys *num-keys) -1)) :> *dm-out]
    (<<if (< *ki *num-keys)
      (nth *key-nodes *ki :> *src)
      (nth *src 0 :> *sr)
      (nth *src 1 :> *sc)
      (bfs *grid *H *W *sr *sc :> *bfs-dist)
      ;; Extract distances to all other key nodes
      (loop<- [*kj 0 *dm *dist-matrix :> *dm2]
        (<<if (< *kj *num-keys)
          (nth *key-nodes *kj :> *dst)
          (nth *dst 0 :> *dr)
          (nth *dst 1 :> *dc)
          (+ (* *dr *W) *dc :> *di)
          (nth *bfs-dist *di :> *d-val)
          (+ (* *ki *num-keys) *kj :> *dm-idx)
          (assoc *dm *dm-idx *d-val :> *dm3)
          (continue> (inc *kj) *dm3)
          (else>)
          (:> *dm)))
      (continue> (inc *ki) *dm2)
      (else>)
      (:> *dist-matrix)))
  (:> *dm-out))

;;; Bitmask DP

(deframafn init-dp [*dm *num-keys *num-candies *num-masks]
  (* *num-masks *num-candies :> *dp-size)
  (vec (repeat *dp-size -1) :> *dp-init)
  (loop<- [*ci 0 *dp *dp-init :> *dp1]
    (<<if (< *ci *num-candies)
      (+ *ci 2 :> *ki-ci)
      ;; dist(S=0, candy ci = ki-ci) — row 0 of dist matrix
      (nth *dm *ki-ci :> *d-s-ci)
      (<<if (not= *d-s-ci -1)
        (bit-shift-left 1 *ci :> *mask-ci)
        (+ (* *mask-ci *num-candies) *ci :> *dp-idx)
        (assoc *dp *dp-idx *d-s-ci :> *dp2)
        (continue> (inc *ci) *dp2)
        (else>)
        (continue> (inc *ci) *dp))
      (else>)
      (:> *dp)))
  (:> *dp1))

;; Expands DP transitions for a single mask: for each ci in mask, try adding each cj not in mask.
(deframafn dp-expand-mask [*mask *dp *dm *num-keys *num-candies]
  (loop<- [*ci 0 *dp-m *dp :> *dp-m2]
    (<<if (< *ci *num-candies)
      (bit-shift-left 1 *ci :> *bit-ci)
      (bit-and *mask *bit-ci :> *has-ci)
      (<<if (not= *has-ci 0)
        (+ (* *mask *num-candies) *ci :> *dp-ci-idx)
        (nth *dp-m *dp-ci-idx :> *dp-ci-val)
        (<<if (not= *dp-ci-val -1)
          (loop<- [*cj 0 *dp-inner *dp-m :> *dp-inner2]
            (<<if (< *cj *num-candies)
              (bit-shift-left 1 *cj :> *bit-cj)
              (bit-and *mask *bit-cj :> *has-cj)
              (<<if (= *has-cj 0)
                (+ *ci 2 :> *ki-i)
                (+ *cj 2 :> *ki-j)
                (+ (* *ki-i *num-keys) *ki-j :> *dm-ij)
                (nth *dm *dm-ij :> *d-ij)
                (<<if (not= *d-ij -1)
                  (+ *dp-ci-val *d-ij :> *new-cost)
                  (bit-or *mask *bit-cj :> *new-mask)
                  (+ (* *new-mask *num-candies) *cj :> *dp-nj-idx)
                  (nth *dp-inner *dp-nj-idx :> *old-val)
                  (or> (= *old-val -1) (< *new-cost *old-val) :> *should-update)
                  (<<if *should-update
                    (assoc *dp-inner *dp-nj-idx *new-cost :> *dp-u)
                    (continue> (inc *cj) *dp-u)
                    (else>)
                    (continue> (inc *cj) *dp-inner))
                  (else>)
                  (continue> (inc *cj) *dp-inner))
                (else>)
                (continue> (inc *cj) *dp-inner))
              (else>)
              (:> *dp-inner)))
          (continue> (inc *ci) *dp-inner2)
          (else>)
          (continue> (inc *ci) *dp-m))
        (else>)
        (continue> (inc *ci) *dp-m))
      (else>)
      (:> *dp-m)))
  (:> *dp-m2))

(deframafn run-dp [*dm *num-keys *num-candies]
  (bit-shift-left 1 *num-candies :> *num-masks)
  (init-dp *dm *num-keys *num-candies *num-masks :> *dp1)
  (loop<- [*mask 1 *dp *dp1 :> *dp-final]
    (<<if (< *mask *num-masks)
      (dp-expand-mask *mask *dp *dm *num-keys *num-candies :> *dp2)
      (continue> (inc *mask) *dp2)
      (else>)
      (:> *dp)))
  (:> *dp-final))

;; Scans the DP table for the maximum candy count reachable within T moves.
(deframafn find-best [*dp *dm *num-keys *num-candies *T]
  (bit-shift-left 1 *num-candies :> *num-masks)
  (loop<- [*mask 0 *best -1 :> *best-out]
    (<<if (< *mask *num-masks)
      (loop<- [*ci 0 *mask-best *best :> *mb2]
        (<<if (< *ci *num-candies)
          (bit-shift-left 1 *ci :> *bit-ci)
          (bit-and *mask *bit-ci :> *has-ci)
          (<<if (not= *has-ci 0)
            (+ (* *mask *num-candies) *ci :> *dp-idx)
            (nth *dp *dp-idx :> *dp-val)
            (<<if (not= *dp-val -1)
              (+ *ci 2 :> *ki-i)
              (+ (* *ki-i *num-keys) 1 :> *dm-ig)
              (nth *dm *dm-ig :> *d-ig)
              (and> (not= *d-ig -1) (<= (+ *dp-val *d-ig) *T) :> *reachable)
              (<<if *reachable
                (Integer/bitCount *mask :> *pop)
                (max *mask-best *pop :> *new-best)
                (continue> (inc *ci) *new-best)
                (else>)
                (continue> (inc *ci) *mask-best))
              (else>)
              (continue> (inc *ci) *mask-best))
            (else>)
            (continue> (inc *ci) *mask-best))
          (else>)
          (:> *mask-best)))
      (continue> (inc *mask) *mb2)
      (else>)
      (:> *best)))
  ;; Also check direct S->G (0 candies)
  (nth *dm 1 :> *sg-dist)
  (and> (not= *sg-dist -1) (<= *sg-dist *T) :> *sg-ok)
  (<<if *sg-ok
    (max *best-out 0 :> *final-best)
    (else>)
    (identity *best-out :> *final-best))
  (:> *final-best))

;;; Top-level entry point

(deframafn solve [*input]
  (parse-input *input :> [*grid *H *W *T])
  (find-key-nodes *grid *H *W :> [*key-nodes *num-candies])
  (count *key-nodes :> *num-keys)
  (build-dist-matrix *grid *H *W *key-nodes :> *dm)
  (<<if (= *num-candies 0)
    (nth *dm 1 :> *sg-dist)
    (and> (not= *sg-dist -1) (<= *sg-dist *T) :> *sg-ok)
    (<<if *sg-ok
      (str 0 :> *answer)
      (else>)
      (str -1 :> *answer))
    (else>)
    (run-dp *dm *num-keys *num-candies :> *dp)
    (find-best *dp *dm *num-keys *num-candies *T :> *best)
    (<<if (= *best -1)
      (str -1 :> *answer)
      (else>)
      (str *best :> *answer)))
  (:> *answer))
