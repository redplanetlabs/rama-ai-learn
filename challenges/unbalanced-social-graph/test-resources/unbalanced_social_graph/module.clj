(ns unbalanced-social-graph.module
  "Reference implementation for the unbalanced-social-graph challenge.

   Materializes a load-balanced view of each account's followers. An
   account's followers are spread across many tasks of the module so that
   downstream fanout can be processed in parallel even when the social graph
   is extremely unbalanced (some accounts with millions of followers, average
   under a thousand).

   Three PStates work together to materialize the partitioned view of
   followers:
     - $$partitioned-followers-control  : target-id -> List<task-id>
         Lives on the target's home task. The set of tasks that hold any
         partition of this account's followers, in allocation order.
     - $$partitioned-follower-tasks     : target-id -> follower-id -> task-id
         Lives on the target's home task. Lookup table for finding which
         task owns a given follower's data.
     - $$partitioned-followers          : target-id -> set<follower-id>
         Distributed across the tasks listed in -control. Each task only
         holds the followers assigned to it.

   Allocation algorithm: every NEW-TASK-CUTOFF followers, a new random task
   is added to -control (until all tasks have been used). After that, new
   followers are assigned round-robin across all tasks.

   In addition, a normal (non-partitioned) PState tracks the inverse view
   keyed by the following account:
     - $$user-followees                  : account-id -> set<target-id>
         Lives on account-id's home task. The set of accounts that
         account-id follows."
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama.ops :as ops]
            [com.rpl.rama.aggs :as aggs]
            [unbalanced-social-graph.protocol :as p]
            [rama-challenges.harness :as harness])
  (:import [com.rpl.rama ModuleInstanceInfo]
           [unbalanced-social-graph.protocol FollowAccount RemoveFollowAccount]))

(def NEW-TASK-CUTOFF 1000)

(defn allocate-target-task
  "Returns [new-tasks-or-nil target-task].

   new-tasks-or-nil is the updated tasks list when $$partitioned-followers-control
   should be rewritten; nil means leave -control unchanged."
  [tasks followers-count current-task-id ^ModuleInstanceInfo info]
  (let [num-tasks (.getNumTasks info)
        cutoff    NEW-TASK-CUTOFF]
    (cond
      (>= followers-count (* num-tasks cutoff))
      [nil (nth tasks (mod followers-count (count tasks)))]

      (zero? (mod followers-count cutoff))
      (let [new-tasks (if (nil? tasks)
                        [current-task-id]
                        (let [taken (set tasks)
                              available (vec (remove taken (range num-tasks)))]
                          (conj (vec tasks) (rand-nth available))))]
        [new-tasks (last new-tasks)])

      :else
      [nil (last tasks)])))

(defmodule SocialGraphModule [setup topologies]
  (declare-depot setup *follow-depot (hash-by :account-id))

  (let [s (stream-topology topologies "social-graph")]
    (declare-pstate s $$partitioned-followers-control
                    {Long java.util.List})
    (declare-pstate s $$partitioned-follower-tasks
                    {Long (map-schema Long Long {:subindex? true})})
    (declare-pstate s $$partitioned-followers
                    {Long (set-schema Long {:subindex? true})})
    (declare-pstate s $$user-followees
                    {Long (set-schema Long {:subindex? true})})

    (<<sources s
      (source> *follow-depot {:retry-mode :all-after} :> *event)
      (<<subsource *event
        (case> FollowAccount :> {:keys [*account-id *target-id]})
        (+compound $$user-followees {*account-id (aggs/+set-agg *target-id)})
        (|hash *target-id)
        (local-select> (keypath *target-id *account-id)
                       $$partitioned-follower-tasks :> *curr-task)
        (<<if (some? *curr-task)
          (identity *curr-task :> *target-task)
         (else>)
          (local-select> (keypath *target-id)
                         $$partitioned-followers-control :> *tasks)
          (local-select> [(keypath *target-id) (view count)]
                         $$partitioned-follower-tasks :> *followers-count)
          (ops/module-instance-info :> *info)
          (ops/current-task-id :> *current-task-id)
          (allocate-target-task *tasks *followers-count *current-task-id *info
                                :> [*new-tasks *target-task])
          (<<if (some? *new-tasks)
            (local-transform> [(keypath *target-id) (termval *new-tasks)]
                              $$partitioned-followers-control))
          (local-transform> [(keypath *target-id *account-id) (termval *target-task)]
                            $$partitioned-follower-tasks))
        (|direct *target-task)
        (+compound $$partitioned-followers
                   {*target-id (aggs/+set-agg *account-id)})

        (case> RemoveFollowAccount :> {:keys [*account-id *target-id]})
        (local-transform> [(keypath *account-id) (set-elem *target-id) NONE>]
                          $$user-followees)
        (|hash *target-id)
        (local-select> [(keypath *target-id) (must *account-id)]
                       $$partitioned-follower-tasks :> *task)
        (|direct *task)
        (local-transform> [(keypath *target-id) (set-elem *account-id) NONE>]
                          $$partitioned-followers))))

  (<<query-topology topologies "get-followers"
    [*target-id :> *followers]
    (|hash *target-id)
    (local-select> (keypath *target-id) $$partitioned-followers-control :> *tasks)
    (ops/explode *tasks :> *task-id)
    (|direct *task-id)
    (local-select> [(keypath *target-id) ALL]
                   $$partitioned-followers
                   {:allow-yield? true}
                   :> *follower-id)
    (|origin)
    (aggs/+set-agg *follower-id :> *followers)))

(defn make-client
  [ipc]
  (let [m            (get-module-name SocialGraphModule)
        follow-depot (foreign-depot ipc m "*follow-depot")
        followees    (foreign-pstate ipc m "$$user-followees")
        followers-q  (foreign-query ipc m "get-followers")]
    (reify p/SocialGraph
      (follow! [_ account-id target-id]
        (foreign-append! follow-depot (->FollowAccount account-id target-id) :ack))
      (unfollow! [_ account-id target-id]
        (foreign-append! follow-depot (->RemoveFollowAccount account-id target-id) :ack))
      (get-followers [_ target-id]
        (or (foreign-invoke-query followers-q target-id) #{}))
      (get-followees [_ account-id]
        (set (foreign-select [(keypath account-id) ALL] followees {:pkey account-id})))

      harness/Synchronizable
      ;; Stream topology with full-ack appends: processing is complete when
      ;; follow!/unfollow! return, so the barrier is a no-op.
      (wait-for-processing! [_]))))

(defn create-module
  []
  {:module      SocialGraphModule
   :wrap-client make-client})
