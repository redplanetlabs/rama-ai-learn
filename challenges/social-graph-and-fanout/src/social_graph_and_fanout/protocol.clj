(ns social-graph-and-fanout.protocol
  "Protocols for the social-graph-and-fanout challenge. Tests exercise the
   module exclusively through these protocols.")

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

(defprotocol Fanout
  "Profile + post fanout module. Implementations materialize a per-user
   timeline of posts written by accounts the user follows.

   account-id and user-id are Long. name, location, and content are String."
  (set-profile! [this account-id name location]
    "Create or update the profile for account-id. Visible within 5 milliseconds.")
  (get-profile [this account-id]
    "Returns a map of form {:name String :location String}, or nil if no
     profile exists.")
  (post! [this account-id content]
    "Publish a post by account-id. Each invocation produces one timeline
     entry per follower. It also adds to the timeline of account-id. Visible
     within 5 milliseconds to the account-id user-timeline. Fanout to follower
     and self timelines may complete asynchronously after that and take up to a second
     (for users who don't have huge number of followers).")
  (get-user-timeline [this account-id]
    "Return every post account-id has ever made as a vector of content
     strings, in chronological order (oldest first).")
  (get-timeline-page [this user-id from-post-id]
    "Return up to 20 most recent timeline entries for user-id, most recent
     first, posted before from-post-id (or all if from-post-id is nil). Each
     entry is a map shaped:

       {:post-id    java.util.UUID  ;; the post's UUID7 — pass back as
                                    ;; from-post-id on the next call to get
                                    ;; the next page
        :account-id Long            ;; poster's account id
        :name       String          ;; poster's CURRENT name from their profile
        :content    String}

     Only the 800 most recent timeline entries per user are visible.

     During normal operation, the work to read a timeline must be FIXED —
     independent of how many accounts user-id follows and how many posts
     those accounts have made. During normal operation, must complete in roughly 50ms.

     During cases where in-memory state must be recovered, like failures or
     module updates, these latency constraints can be loosened significantly.
     A recovered timeline may include followee posts made before the follow
     and may omit entries from accounts unfollowed after delivery."))
