(ns anti.solution
  "Reference implementation for anti challenge."
  (:require [clojure.string :as str]
            [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

(def ^:private MOD 998244353)
(def ^:private ZERO-VEC (vec (repeat 27 0)))

;;; Helper deframafns

(deframafn mod-pow [*base *exp]
  (mod *base MOD :> *b0)
  (loop<- [*b *b0 *e *exp *res 1 :> *result]
          (<<if (zero? *e)
                (:> *res)
                (else>)
                (<<if (odd? *e)
                      (mod (* *res *b) MOD :> *nr)
                      (else>)
                      (identity *res :> *nr))
                (mod (* *b *b) MOD :> *nb)
                (quot *e 2 :> *ne)
                (continue> *nb *ne *nr)))
  (:> *result))

(deframafn mod-inv [*a]
  (- MOD 2 :> *exp)
  (mod-pow *a *exp :> *result)
  (:> *result))

(deframafn update-dp0-for-question [*dp0]
  (loop<- [*nn 0 *r ZERO-VEC :> *result]
          (<<if (> *nn 26)
                (:> *r)
                (else>)
                (get *dp0 *nn :> *v)
                (get *r *nn :> *cur)
                (mod (* *v 26) MOD :> *lc)
                (mod (+ *cur *lc) MOD :> *nc)
                (assoc *r *nn *nc :> *r1)
                (<<if (< *nn 26)
                      (mod (* *v (- 26 *nn)) MOD :> *stay)
                      (inc *nn :> *nn1)
                      (get *r1 *nn1 :> *c1)
                      (mod (+ *c1 *stay) MOD :> *nc1)
                      (assoc *r1 *nn1 *nc1 :> *r2)
                      (continue> (inc *nn) *r2)
                      (else>)
                      (continue> (inc *nn) *r1))))
  (:> *result))

(deframafn compute-advancing [*dp0]
  (loop<- [*nn 1 *acc 0 :> *result]
          (<<if (> *nn 26)
                (:> *acc)
                (else>)
                (get *dp0 *nn :> *v)
                (mod (* *v *nn) MOD :> *p)
                (mod (+ *acc *p) MOD :> *na)
                (continue> (inc *nn) *na)))
  (:> *result))

(deframafn split-dp0 [*dp0 *dsz *invd]
  (loop<- [*nn 0 *r ZERO-VEC *asum 0 :> *ndp0 *advsum]
          (<<if (> *nn 26)
                (:> *r *asum)
                (else>)
                (get *dp0 *nn :> *v)
                (<<if (zero? *v)
                      (continue> (inc *nn) *r *asum)
                      (else>)
                      (- *nn *dsz :> *diff)
                      (mod (* *v *diff) MOD :> *t1)
                      (mod (* *t1 *invd) MOD :> *adv-part)
                      (- 26 *nn :> *gap)
                      (mod (* *v *gap) MOD :> *t2)
                      (mod (* *t2 *invd) MOD :> *stay)
                      (inc *nn :> *nn1)
                      (<<if (< *nn1 27)
                            (get *r *nn1 :> *c1)
                            (mod (+ *c1 *stay) MOD :> *nc1)
                            (assoc *r *nn1 *nc1 :> *r2)
                            (else>)
                            (identity *r :> *r2))
                      (mod (+ *asum *adv-part) MOD :> *nsum)
                      (continue> (inc *nn) *r2 *nsum))))
  (:> *ndp0 *advsum))

;;; Main solve

(deframafn solve [*input]
  (str/trim *input :> *s)
  (count *s :> *n)
  (assoc ZERO-VEC 0 1 :> *init-dp0)

  ;; DP tracking progress toward DDoS-type subsequence XXxY:
  ;; Phase 0 (dp0[n]): no pair formed, n = |S| (uppercase letters seen)
  ;; Phase 1 (dp1): pair XX formed, no lowercase after
  ;; Phase 2 (dp2): triple XXx formed
  ;; D: set of definite uppercase letter indices seen during Phase 0
  (loop<- [*i 0 *dp0 *init-dp0 *dp1 0 *dp2 0 *D #{} :> *answer]
          (<<if (= *i *n)
                (reduce + 0 *dp0 :> *raw)
                (mod (+ *raw *dp1 *dp2) MOD :> *total)
                (str *total :> *ans)
                (:> *ans)

                (else>)
                (nth *s *i :> *ch)
                (int *ch :> *code)

                (<<cond
        ;;; ? character (52 options: 26 lowercase + 26 uppercase)
                 (case> (= *code 63))
                 (update-dp0-for-question *dp0 :> *ndp0)
                 (compute-advancing *dp0 :> *adv)
                 (mod (* *dp1 26) MOD :> *dp1x26)
                 (mod (+ *dp1x26 *adv) MOD :> *ndp1)
                 (mod (* *dp2 26) MOD :> *dp2x26)
                 (mod (+ *dp1x26 *dp2x26) MOD :> *ndp2)
                 (continue> (inc *i) *ndp0 *ndp1 *ndp2 *D)

        ;;; Uppercase letter
                 (case> (<= *code 90))
                 (- *code 65 :> *cidx)
                 (contains? *D *cidx :> *inD)
                 (<<if *inD
          ;; C in D: all Phase 0 advance to Phase 1, Phase 2 invalid
                       (reduce + 0 *dp0 :> *t0)
                       (mod (+ *dp1 *t0) MOD :> *ndp1)
                       (continue> (inc *i) ZERO-VEC *ndp1 0 *D)

                       (else>)
          ;; C not in D: split Phase 0 by symmetry
                       (count *D :> *dsz)
                       (- 26 *dsz :> *denom)
                       (mod-inv *denom :> *invd)
                       (split-dp0 *dp0 *dsz *invd :> *ndp0 *advsum)
                       (mod (+ *dp1 *advsum) MOD :> *ndp1)
                       (conj *D *cidx :> *nD)
                       (continue> (inc *i) *ndp0 *ndp1 0 *nD))

        ;;; Lowercase letter: Phase 1 → Phase 2
                 (default>)
                 (mod (+ *dp2 *dp1) MOD :> *ndp2)
                 (continue> (inc *i) *dp0 0 *ndp2 *D))))

  (:> *answer))
