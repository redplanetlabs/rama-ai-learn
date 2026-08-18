(ns word-count.protocol
  "Protocol for the word-count challenge. Tests exercise the module
   exclusively through this protocol.")

(defprotocol WordCount
  "Read/write surface over a running count of how often each word has been
   seen across all sentences added so far.

   A sentence's words are its maximal runs of non-whitespace characters.
   Two words are the same word when they are equal ignoring case, so
   `Dog`, `dog`, and `DOG` all count toward the same total."
  (add-sentence! [this sentence]
    "Record every word occurrence in sentence (a String). A word repeated
     within one sentence counts once per occurrence. A sentence with no
     words, such as an empty or whitespace-only String, records nothing.")
  (get-count [this word]
    "Return the number of times word has been seen across all sentences
     added so far, as a Long. Returns 0 for a word that has never been
     seen. Matching ignores case. Allowed to be out of date by a few seconds."))
