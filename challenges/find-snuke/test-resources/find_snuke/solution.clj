(ns find-snuke.solution
  "Reference implementation for find-snuke challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [clojure.string :as str]))

(def ^:private DIRS [[-1 -1] [-1 0] [-1 1] [0 -1] [0 1] [1 -1] [1 0] [1 1]])
(def ^:private TARGET "snuke")

;;; Check if "snuke" starts at (r,c) going in direction (dr,dc).
;;; Returns a vector of 5 [row col] pairs (0-indexed), or nil if not found.
(deframafn check-snuke [*grid *H *W *r *c *dr *dc]
  (loop<- [*step 0 *cells [] :> *result]
          (<<if (= *step 5)
                (:> *cells)
                (else>)
                (+ *r (* *step *dr) :> *nr)
                (+ *c (* *step *dc) :> *nc)
                (and> (>= *nr 0) (< *nr *H) (>= *nc 0) (< *nc *W) :> *in-bounds)
                (<<if *in-bounds
                      (nth *grid *nr :> *row)
                      (nth *row *nc :> *ch)
                      (nth TARGET *step :> *expected)
                      (<<if (= *ch *expected)
                            (conj *cells [*nr *nc] :> *cells2)
                            (continue> (inc *step) *cells2)
                            (else>)
                            (:> nil))
                      (else>)
                      (:> nil))))
  (:> *result))

;;; Search all 8 directions from (r,c) for "snuke".
;;; Returns cells vector if found, nil otherwise.
(deframafn search-dirs [*grid *H *W *r *c]
  (loop<- [*di 0 :> *found]
          (<<if (= *di 8)
                (:> nil)
                (else>)
                (nth DIRS *di :> *dir)
                (nth *dir 0 :> *dr)
                (nth *dir 1 :> *dc)
                (check-snuke *grid *H *W *r *c *dr *dc :> *cells)
                (<<if (some? *cells)
                      (:> *cells)
                      (else>)
                      (continue> (inc *di)))))
  (:> *found))

(deframafn solve [*input]
  (str/split-lines *input :> *lines)
  (str/split (nth *lines 0) #"\s+" :> *parts)
  (parse-long (nth *parts 0) :> *H)
  (parse-long (nth *parts 1) :> *W)
  ;; Build grid vector from input lines (skip first header line)
  (vec (rest *lines) :> *grid-done)
  ;; Search each cell for an 's' that starts "snuke" in some direction
  (loop<- [*i 0 :> *cells]
          (<<if (= *i *H)
                (:> nil)
                (else>)
                (nth *grid-done *i :> *row)
                (loop<- [*j 0 :> *row-result]
                        (<<if (= *j *W)
                              (:> nil)
                              (else>)
                              (nth *row *j :> *ch)
                              (<<if (= *ch \s)
                                    (search-dirs *grid-done *H *W *i *j :> *found)
                                    (<<if (some? *found)
                                          (:> *found)
                                          (else>)
                                          (continue> (inc *j)))
                                    (else>)
                                    (continue> (inc *j)))))
                (<<if (some? *row-result)
                      (:> *row-result)
                      (else>)
                      (continue> (inc *i)))))
  ;; Format output: five 1-indexed "row col" lines
  (loop<- [*k 0 *sb "" :> *output]
          (<<if (= *k 5)
                (:> *sb)
                (else>)
                (nth *cells *k :> *cell)
                (nth *cell 0 :> *cr)
                (nth *cell 1 :> *cc)
                (inc *cr :> *r1)
                (inc *cc :> *c1)
                (str *sb *r1 " " *c1 "\n" :> *sb2)
                (continue> (inc *k) *sb2)))
  (str/trim *output :> *trimmed)
  (:> *trimmed))
