(ns word-count.module
  "Reference implementation for the word-count challenge.

   One depot of sentences feeds a microbatch topology that explodes each
   sentence into its words and accumulates per-word totals.

     - $$word-counts : word -> Long

   Microbatch rather than stream: the write is a counter increment, which is
   not idempotent, so a stream retry would double count. Microbatch gives
   exactly-once PState updates across retries of the same microbatch.

   Counts are keyed by word, so they spread across the cluster by word and
   `get-count` is a single point read on the word's home task. The skew in
   word frequency is handled by aggregating with a combiner inside a batch
   block, which gets two-phase treatment: every task folds its own words into
   one local map first, so a common word crosses the network once per task per
   microbatch rather than once per occurrence."
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama.ops :as ops]
            [com.rpl.rama.test :as rtest]
            [word-count.protocol :as p]
            [rama-challenges.harness :as harness]))

(defn- normalize-word
  "Case-folded form of a word. Two words are the same word when their
   normalized forms are equal."
  [word]
  (.toLowerCase ^String word))

(defn- sentence-words
  "The words of a sentence: maximal runs of non-whitespace characters,
   normalized. Returns an empty vector for a sentence with no words."
  [sentence]
  (if (nil? sentence)
    []
    (into []
          (comp (remove empty?) (map normalize-word))
          (.split ^String sentence "\\s+"))))

(def +merge-counts
  "Combines word -> count maps by summing per word. A combiner rather than an
   accumulator so Rama applies two-phase aggregation: each task folds all of
   its own words into one local map and ships that single partial across the
   final pre-agg partitioner, instead of one message per word occurrence.
   That is what keeps a skewed vocabulary from hammering one task.

   `:flush-required? true` because the aggregate state is a map that grows
   with the distinct vocabulary rather than staying a fixed size."
  (combiner (fn [a b] (merge-with + a b))
            :init-fn (fn [] {})
            :flush-required? true))

(defmodule WordCountModule [setup topologies]
  ;; Sentences carry no key worth colocating on, and the topology repartitions
  ;; by word anyway, so spread appends evenly across tasks.
  (declare-depot setup *sentence-depot :random)

  (let [mb (microbatch-topology topologies "counts")]
    (declare-pstate mb $$word-counts {String Long})

    (<<sources mb
      (source> *sentence-depot :> %microbatch)

      (<<batch
        (%microbatch :> *sentence)
        (sentence-words *sentence :> *words)
        (ops/explode *words :> *word)
        ;; Two-phase: each task folds its own words into one local map before
        ;; anything crosses the partitioner, so a common word costs one message
        ;; per task per microbatch instead of one per occurrence. +compound
        ;; cannot do this, since a PState update per event is accumulator style.
        (|hash *word)
        (+merge-counts $$word-counts {*word 1})))))

(defn make-client
  [ipc]
  (let [module-name    (get-module-name WordCountModule)
        sentence-depot (foreign-depot ipc module-name "*sentence-depot")
        counts         (foreign-pstate ipc module-name "$$word-counts")
        ;; Cumulative count of appends, for wait-for-processing!.
        cnt            (atom 0)]
    (reify p/WordCount
      (add-sentence! [_ sentence]
        (swap! cnt inc)
        (foreign-append! sentence-depot sentence :append-ack)
        nil)

      (get-count [_ word]
        (let [w (normalize-word word)]
          (or (foreign-select-one (keypath w) counts {:pkey w}) 0)))

      harness/Synchronizable
      ;; All processing happens in the "counts" microbatch topology, which is
      ;; decoupled from the depot append, so wait on its processed count.
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc module-name "counts" @cnt)))))

(defn create-module
  "Returns the module descriptor for the word-count challenge."
  []
  {:module      WordCountModule
   :wrap-client make-client})
