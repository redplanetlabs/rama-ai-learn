(ns unbalanced-social-graph.protocol
  "Protocol for the unbalanced-social-graph challenge. Tests exercise the
   module exclusively through this protocol.")

(defprotocol SocialGraph
  "Read/write surface over a social graph. `account-id` and `target-id`
   are Long.

   follow!/unfollow! append events to the follow depot. The follower set
   and followee set become consistent within a wait-for-processing! barrier."
  (follow! [this account-id target-id]
    "Record that account-id now follows target-id. Idempotent.")
  (unfollow! [this account-id target-id]
    "Record that account-id no longer follows target-id. Idempotent.")
  (get-followers [this target-id]
    "Return the set of all account-ids that currently follow target-id.
     Empty set when target-id has no followers.")
  (get-followees [this account-id]
    "Return the set of all target-ids that account-id currently follows.
     Empty set when account-id follows no one."))
