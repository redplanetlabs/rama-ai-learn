(ns find-the-losers-of-the-circular-game.solution
  "Reference implementation for find-the-losers-of-the-circular-game challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

(deframafn circular-game-losers [*n *k]
  ;; Simulate the game: track which friends (0-indexed) received the ball.
  ;; On turn i, the ball moves i*k steps forward from current position.
  ;; Game ends when a friend receives the ball a second time.
  (loop<- [*pos 0 *turn 1 *visited #{0} :> *final-visited]
    (* *turn *k :> *step)
    (+ *pos *step :> *raw)
    (rem *raw *n :> *next-pos)
    (<<if (contains? *visited *next-pos)
      (:> *visited)
      (else>)
      (conj *visited *next-pos :> *new-visited)
      (continue> *next-pos (inc *turn) *new-visited)))
  ;; Collect losers: friends (1-indexed) who never received the ball
  (loop<- [*i 0 *losers [] :> *result]
    (<<if (< *i *n)
      (<<if (contains? *final-visited *i)
        (continue> (inc *i) *losers)
        (else>)
        (conj *losers (inc *i) :> *new-losers)
        (continue> (inc *i) *new-losers))
      (else>)
      (:> *losers)))
  (:> *result))
