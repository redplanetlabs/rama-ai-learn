(ns fanout.module
  "Reference implementation for the fanout challenge."
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama.ops :as ops]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [fanout.protocol]
            [fanout.social-graph :as sg]
            [rama-challenges.harness :as harness]
            [rama-challenges.shared :as shared])
  (:import [com.rpl.rama ModuleInstanceInfo]
           [com.rpl.rama.integration TaskGlobalObject TaskGlobalContext]
           [java.util HashMap UUID]))

(def MAX-TIMELINE-ENTRIES 800)
(def MAX-FOLLOWERS-PER-TASK-PER-MB 1000)
(def RECONSTRUCT-FOLLOWEE-LIMIT 300)

(defrecord ProfileEvent [account-id name location])
(defrecord PostEvent [account-id post-id content])

(definterface IUserTimeline
  (^void appendTriple [^long a ^long b ^long c])
  (^void resetWith [^java.util.List entries])
  (^java.util.List recentMostFirst [^java.util.UUID from-post-id ^long limit])
  (^long getLastMbId [])
  (^void setLastMbId [^long mb-id]))

(deftype UserTimeline
  [^longs data
   max-entries
   ^:unsynchronized-mutable write-pos
   ^:unsynchronized-mutable sz
   ^:unsynchronized-mutable last-mb-id]
  IUserTimeline
  (appendTriple [_ a b c]
    (aset data write-pos a)
    (aset data (unchecked-inc write-pos) b)
    (aset data (unchecked-add write-pos 2) c)
    (let [len (alength data)
          new-pos (rem (+ write-pos 3) len)]
      (set! write-pos new-pos))
    (when (< sz max-entries)
      (set! sz (unchecked-inc sz))))
  (resetWith [this entries]
    (set! write-pos 0)
    (set! sz 0)
    (doseq [[a b c] (take max-entries entries)]
      (.appendTriple this a b c)))
  (recentMostFirst [_ from-post-id limit]
    (let [len (alength data)
          from-msb (when from-post-id (.getMostSignificantBits ^UUID from-post-id))
          from-lsb (when from-post-id (.getLeastSignificantBits ^UUID from-post-id))]
      (loop [i 1
             out (transient [])]
        (if (or (> i sz) (>= (count out) limit))
          (persistent! out)
          (let [pos (mod (+ (- write-pos (* 3 i)) len) len)
                a (aget data pos)
                b (aget data (+ pos 1))
                c (aget data (+ pos 2))]
            (if (or (nil? from-post-id)
                    (< b from-msb)
                    (and (= b from-msb) (< c from-lsb)))
              (recur (inc i) (conj! out [a b c]))
              (recur (inc i) out)))))))
  (getLastMbId [_] last-mb-id)
  (setLastMbId [_ mb-id] (set! last-mb-id mb-id)))

(defn make-user-timeline ^UserTimeline []
  (UserTimeline. (long-array (* MAX-TIMELINE-ENTRIES 3))
                 MAX-TIMELINE-ENTRIES
                 0
                 0
                 Long/MIN_VALUE))

(definterface ITimelineCache
  (^boolean isInitialized [^long user-id])
  (^void initialize [^long user-id ^java.util.List entries])
  (^void appendIfNew [^long user-id ^long mb-id ^java.util.List entries])
  (^java.util.List recent [^long user-id ^java.util.UUID from-post-id ^long limit]))

(deftype TimelineCache
  ;; A user-id appears in `timelines` iff it has been initialized.
  ;; The UserTimeline carries its own last-mb-id, so a single map is enough.
  ;; The microbatch topology only delivers to users that are already initialized
  ;; (i.e. have read their timeline at least once) — uninitialized users will
  ;; pick up the missing entries via reconstruction on their next read.
  [^HashMap timelines]
  TaskGlobalObject
  (prepareForTask [_ _ _])
  (close [_])

  ITimelineCache
  (isInitialized [_ user-id]
    (.containsKey timelines user-id))
  (initialize [_ user-id entries]
    ;; Idempotent: only the first call populates; subsequent calls are no-ops
    ;; so the same query topology can be invoked unconditionally on every read.
    (when-not (.containsKey timelines user-id)
      (let [ut (make-user-timeline)]
        (.resetWith ut entries)
        (.put timelines user-id ut))))
  (appendIfNew [_ user-id mb-id entries]
    (when-let [ut ^UserTimeline (.get timelines user-id)]
      (when-not (= (.getLastMbId ut) mb-id)
        (doseq [triple entries]
          (let [[a b c] triple]
            (.appendTriple ut (long a) (long b) (long c))))
        (.setLastMbId ut (long mb-id)))))
  (recent [_ user-id from-post-id limit]
    (if-let [ut (.get timelines user-id)]
      (.recentMostFirst ^UserTimeline ut from-post-id limit)
      [])))

(defn make-timeline-cache []
  (TimelineCache. (HashMap.)))

(defn cache-initialized? [^TimelineCache c user-id]
  (.isInitialized c user-id))

(defn cache-recent [^TimelineCache c user-id from-post-id limit]
  (.recent c user-id from-post-id limit))

(defn cache-append-if-new! [^TimelineCache c user-id mb-id entries]
  (.appendIfNew c user-id mb-id entries))

(defn cache-initialize! [^TimelineCache c user-id entries]
  (.initialize c user-id entries))

(defn uuid-msb ^long [^UUID u] (.getMostSignificantBits u))
(defn uuid-lsb ^long [^UUID u] (.getLeastSignificantBits u))
(defn ->uuid ^UUID [^long msb ^long lsb] (UUID. msb lsb))

(defn sort-triples-for-init
  "Sorts triples chronologically (by UUID7 = msb,lsb) and keeps only the most
   recent `limit` entries. Returned vector is oldest-first so that appending
   them in order to the ring buffer leaves the most recent entry at the head."
  [triples limit]
  (->> triples
       (sort-by (fn [[_ msb lsb]] [msb lsb]))
       (take-last limit)
       vec))

(defn build-timeline-entry [post-id poster-id profile content]
  {:post-id    post-id
   :account-id poster-id
   :name       (:name profile)
   :content    content})

(defn order-by-index
  "Restore the order `cache-recent` returned. The per-poster fan-out that
   fetches names/content reorders entries as they return to origin, so each
   carries its original page index; sort by it and strip it. Pagination uses
   the page's last post-id as the cursor, so the page must preserve the
   buffer's order, not re-sort by post-id."
  [indexed]
  (mapv second (sort-by first indexed)))

(def social-graph-module-name (get-module-name sg/SocialGraphModule))

(defn sorted-set-last [^java.util.SortedSet s] (.last s))

(defmodule FanoutModule [setup topologies]
  (declare-depot setup *profile-depot (hash-by :account-id))
  (declare-depot setup *post-depot    (hash-by :account-id))
  (if shared/REPLACE-TICK-DEPOTS
    (declare-depot setup *process-tick :random {:global? true})
    (declare-tick-depot setup *process-tick 1000))

  (mirror-pstate setup $$mirror-control    social-graph-module-name "$$partitioned-followers-control")
  (mirror-pstate setup $$mirror-followers  social-graph-module-name "$$partitioned-followers")
  (mirror-pstate setup $$mirror-followees  social-graph-module-name "$$user-followees")

  (declare-object setup *cache (make-timeline-cache))

  (let [s (stream-topology topologies "writes")]
    (declare-pstate s $$profiles
                    {Long (fixed-keys-schema {:name String :location String})})
    (declare-pstate s $$user-posts
                    {Long (map-schema UUID String {:subindex? true})})

    (<<sources s
      (source> *profile-depot :> {:keys [*account-id *name *location]})
      (local-transform> [(keypath *account-id)
                         (termval {:name *name :location *location})]
                        $$profiles)

      (source> *post-depot :> {:keys [*account-id *post-id *content]})
      (local-transform> [(keypath *account-id *post-id) (termval *content)]
                        $$user-posts)))

  ;; ---------- Microbatch topology: chunked fanout ----------
  ;; $$pending-fanouts is a flat map keyed by [poster-id post-id sg-task-id]
  ;; with the cursor as the value. Entries live on whichever of our tasks the
  ;; natural processing flow places them on; Batch 1 iterates them via |all.
  ;; The sg-task-id is stored in the key so Batch 1 knows which mirror task to
  ;; query, and the mirror partitioner does not move our task position.
  (let [mb (microbatch-topology topologies "fanout")]
    (declare-pstate mb $$pending-fanouts {java.util.List Long})

    (<<sources mb
      ;; forces the microbatch to run and processing $$pending-fanouts even if there's no incoming posts
      (source> *process-tick :> %mb)
      (%mb)

      (source> *post-depot :> %microbatch)
      (<<batch
        (|all)
        (local-select> ALL $$pending-fanouts {:allow-yield? true} :> [[*poster-id *post-id *sg-task-id :as *key] *cursor])
        (|direct$$ $$mirror-followers *sg-task-id)
        (local-select> [(keypath *poster-id)
                        (sorted-set-range-from *cursor
                                               MAX-FOLLOWERS-PER-TASK-PER-MB
                                               {:inclusive-start? false})]
                       $$mirror-followers
                       :> *follower-set)
        (<<if (< (count *follower-set) MAX-FOLLOWERS-PER-TASK-PER-MB)
          (local-transform> [(keypath *key) NONE>] $$pending-fanouts)
         (else>)
          (sorted-set-last *follower-set :> *new-cursor)
          (local-transform> [(keypath *key) (termval *new-cursor)]
                            $$pending-fanouts))
        (materialize> *poster-id *post-id *follower-set :> $$from-last-round))
      (<<batch
        ;; Branch <curr>: new posts read first chunk, save remainder to pending
        (%microbatch :> {:keys [*account-id *post-id]})
        (anchor> <fanout-root>)
        (identity *account-id :> *target-id)
        (anchor> <self>)

        (hook> <fanout-root>)
        (|hash$$ $$mirror-control *account-id)
        (local-select> (keypath *account-id) $$mirror-control :> *sg-tasks)
        (ops/explode *sg-tasks :> *sg-task-id)
        (|direct$$ $$mirror-followers *sg-task-id)
        (local-select> [(keypath *account-id)
                        (sorted-set-range-from-start MAX-FOLLOWERS-PER-TASK-PER-MB)]
                       $$mirror-followers
                       :> *follower-set)
        (<<if (>= (count *follower-set) MAX-FOLLOWERS-PER-TASK-PER-MB)
          (sorted-set-last *follower-set :> *new-cursor)
          (vector *account-id *post-id *sg-task-id :> *key)
          (local-transform> [(keypath *key) (termval *new-cursor)]
                            $$pending-fanouts))
        (ops/explode *follower-set :> *target-id)
        (anchor> <followers>)

        (unify> <self> <followers>)
        (anchor> <curr>)

        ;; Branch <pending>: read materialized follower vectors from Batch 1
        (gen>)
        ($$from-last-round :> *account-id *post-id *follower-set)
        (ops/explode *follower-set :> *target-id)
        (anchor> <pending>)

        (unify> <curr> <pending>)
        (uuid-msb *post-id :> *post-msb)
        (uuid-lsb *post-id :> *post-lsb)
        (vector *account-id *post-msb *post-lsb :> *tuple)
        (+group-by *target-id
          (aggs/+vec-agg *tuple :> *deliveries))
        (ops/current-microbatch-id :> *mb-id)
        (cache-append-if-new! *cache *target-id *mb-id *deliveries))))

  (<<query-topology topologies "get-user-timeline"
    [*account-id :> *contents]
    (|hash *account-id)
    (local-select> [(keypath *account-id) (subselect MAP-VALS)]
                   $$user-posts
                   {:allow-yield? true}
                   :> *contents)
    (|origin))

  (<<query-topology topologies "compute-timeline-init"
    [*user-id :> *sorted-triples]
    (|hash$$ $$mirror-followees *user-id)
    (local-select> [(keypath *user-id)
                    (sorted-set-range-from-start RECONSTRUCT-FOLLOWEE-LIMIT)
                    ALL]
      $$mirror-followees
      :> *followee-id)
    (|hash *followee-id)
    (local-select> [(keypath *followee-id) (sorted-map-range-to-end 10) MAP-KEYS]
                   $$user-posts
                   :> *post-id)
    (uuid-msb *post-id :> *msb)
    (uuid-lsb *post-id :> *lsb)
    (vector *followee-id *msb *lsb :> *triple)
    (|origin)
    (aggs/+vec-agg *triple :> *all-triples)
    (sort-triples-for-init *all-triples MAX-TIMELINE-ENTRIES :> *sorted-triples))

  (<<query-topology topologies "reconstruct-timeline"
    [*user-id :> *done]
    (|hash *user-id)
    (invoke-query "compute-timeline-init" *user-id :> *sorted-triples)
    (cache-initialize! *cache *user-id *sorted-triples)
    (|origin)
    (identity :ok :> *done))

  (<<query-topology topologies "get-timeline"
    [*user-id *from-post-id :> *result]
    (|hash *user-id)
    (<<if (not (cache-initialized? *cache *user-id))
      (invoke-query "reconstruct-timeline" *user-id))
    (cache-recent *cache *user-id *from-post-id 20 :> *triples)
    (ops/explode-indexed *triples :> *idx [*poster-id *msb *lsb])
    (->uuid *msb *lsb :> *post-id)
    (|hash *poster-id)
    (local-select> (keypath *poster-id) $$profiles :> *profile)
    (local-select> (keypath *poster-id *post-id) $$user-posts :> *content)
    (build-timeline-entry *post-id *poster-id *profile *content :> *entry)
    (|origin)
    (aggs/+vec-agg [*idx *entry] :> *indexed)
    (order-by-index *indexed :> *result)))

(defn make-client
  [ipc]
  (let [m (get-module-name FanoutModule)
        profile-depot (foreign-depot ipc m "*profile-depot")
        post-depot    (foreign-depot ipc m "*post-depot")
        user-posts    (foreign-pstate ipc m "$$user-posts")
        get-tl        (foreign-query ipc m "get-timeline")
        get-utl       (foreign-query ipc m "get-user-timeline")
        mb-cnt        (atom 0)]
    (reify fanout.protocol/Fanout
      (set-profile! [_ account-id name location]
        (foreign-append! profile-depot
                         (->ProfileEvent account-id name location)))
      (get-profile [_ account-id]
        (foreign-select-one (keypath account-id)
                            (foreign-pstate ipc m "$$profiles")
                            {:pkey account-id}))
      (post! [_ account-id content]
        (swap! mb-cnt inc)
        (let [post-id (ops/random-uuid7)]
          (foreign-append! post-depot
                           (->PostEvent account-id post-id content))))
      (get-user-timeline [_ account-id]
        (foreign-invoke-query get-utl account-id))
      (get-timeline-page [_ user-id from-post-id]
        (foreign-invoke-query get-tl user-id from-post-id))

      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc m "fanout" @mb-cnt)))))

(defn create-module
  []
  {:module      FanoutModule
   :wrap-client make-client})
