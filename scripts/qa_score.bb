#!/usr/bin/env bb
;; Score answer claims against corpus claims for a Q&A challenge.
;; Usage: bb qa-score <challenge-name>
;;
;; Reads claims.edn (answer claims) and corpus-claims.edn (verified corpus claims).
;; Two batched LLM calls: one for faithfulness, one for completeness.
;; Writes scores.edn with precision, recall, F1.

(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[cheshire.core :as json])

(def project-root (str (fs/canonicalize ".")))

(defn challenge-dir [name]
  (fs/path project-root "challenges" name))

(defn read-edn-file [path]
  (when-not (fs/exists? path)
    (println "ERROR: file not found:" (str path))
    (System/exit 1))
  (edn/read-string (slurp (str path))))

(defn call-claude [prompt]
  (let [tmp (str (fs/create-temp-file {:prefix "qa-score-" :suffix ".txt"}))
        _   (spit tmp prompt)
        result (proc/shell {:out :string :err :string
                            :dir (str (fs/temp-dir))}
                           "claude" "-p"
                           "--system-prompt-file" tmp
                           "Evaluate now. Return only the JSON array.")
        _   (fs/delete tmp)]
    (:out result)))

(defn parse-yn-array
  "Parse a JSON array of YES/NO strings into a vector of booleans."
  [response expected-count]
  (let [trimmed (str/trim response)
        json-match (re-find #"(?s)\[.*\]" trimmed)]
    (when-not json-match
      (println "ERROR: no JSON array in LLM response:" trimmed)
      (System/exit 1))
    (let [arr (json/parse-string json-match)]
      (when (not= (count arr) expected-count)
        (println (format "WARNING: expected %d results, got %d" expected-count (count arr))))
      (mapv #(str/starts-with? (str/upper-case (str %)) "YES") arr))))

(defn numbered-list [claims]
  (str/join "\n" (map-indexed (fn [i c] (str (inc i) ". " c)) claims)))

(defn check-faithfulness
  "Batch check: is each answer claim supported by corpus claims?
   Returns a vector of booleans."
  [answer-claims corpus-claims]
  (let [prompt (str "You are checking whether claims are supported by verified reference claims.\n\n"
                    "Reference claims:\n<reference>\n" (numbered-list corpus-claims) "\n</reference>\n\n"
                    "Claims to check:\n<check>\n" (numbered-list answer-claims) "\n</check>\n\n"
                    "For each claim in <check>, determine if it is supported by or consistent with the reference claims.\n"
                    "Return a JSON array with one element per claim: \"YES\" if supported, \"NO\" if not.\n"
                    "Example for 3 claims: [\"YES\", \"NO\", \"YES\"]")]
    (parse-yn-array (call-claude prompt) (count answer-claims))))

(defn check-completeness
  "Batch check: is each corpus claim covered by answer claims?
   Returns a vector of booleans."
  [corpus-claims answer-claims]
  (let [prompt (str "You are checking whether expected claims are covered by a set of actual claims.\n\n"
                    "Actual claims:\n<actual>\n" (numbered-list answer-claims) "\n</actual>\n\n"
                    "Expected claims to check:\n<expected>\n" (numbered-list corpus-claims) "\n</expected>\n\n"
                    "For each expected claim, determine if its meaning is expressed in any of the actual claims.\n"
                    "Return a JSON array with one element per expected claim: \"YES\" if covered, \"NO\" if not.\n"
                    "Example for 3 expected claims: [\"YES\", \"NO\", \"YES\"]")]
    (parse-yn-array (call-claude prompt) (count corpus-claims))))

(defn -main [args]
  (when (empty? args)
    (println "Usage: bb qa-score <challenge-name>")
    (System/exit 1))
  (let [challenge-name (first args)
        dir            (challenge-dir challenge-name)
        challenge      (read-edn-file (fs/path dir "challenge.edn"))
        corpus-data    (read-edn-file (fs/path dir "corpus-claims.edn"))
        impl-dir       (fs/path project-root "implementations" challenge-name)
        claims-data    (read-edn-file (fs/path impl-dir "claims.edn"))
        answer-claims  (:claims claims-data)
        corpus-claims  (:claims corpus-data)
        pass-threshold (or (:pass-threshold challenge) 0.7)]

    (when (empty? answer-claims)
      (println "ERROR: no answer claims in claims.edn")
      (System/exit 1))
    (when (empty? corpus-claims)
      (println "ERROR: no corpus claims in corpus-claims.edn")
      (System/exit 1))

    ;; Faithfulness (precision): is each answer claim supported by corpus claims?
    (print (format "Checking faithfulness (%d answer claims)... " (count answer-claims)))
    (flush)
    (let [faithful (check-faithfulness answer-claims corpus-claims)]
      (println "done")
      (doseq [[c r] (map vector answer-claims faithful)]
        (println (str "  " (if r "✓" "✗") " " c)))

      ;; Completeness (recall): is each corpus claim covered by answer claims?
      (print (format "Checking completeness (%d corpus claims)... " (count corpus-claims)))
      (flush)
      (let [covered (check-completeness corpus-claims answer-claims)]
        (println "done")
        (doseq [[c r] (map vector corpus-claims covered)]
          (println (str "  " (if r "✓" "✗") " " c)))

        (let [precision (if (seq faithful)
                          (/ (double (count (filter true? faithful)))
                             (count faithful))
                          0.0)
              recall    (if (seq covered)
                          (/ (double (count (filter true? covered)))
                             (count covered))
                          0.0)
              f1        (if (pos? (+ precision recall))
                          (/ (* 2.0 precision recall) (+ precision recall))
                          0.0)
              passed?   (>= f1 pass-threshold)
              scores    {:challenge      challenge-name
                         :scored-at      (str (java.time.Instant/now))
                         :precision      precision
                         :recall         recall
                         :f1             f1
                         :pass-threshold pass-threshold
                         :passed         passed?
                         :faithful       faithful
                         :covered        covered
                         :answer-claims  answer-claims
                         :corpus-claims  corpus-claims}
              out-path  (str (fs/path impl-dir "scores.edn"))]

          (spit out-path (pr-str scores))
          (println)
          (println (format "Precision: %.2f  Recall: %.2f  F1: %.2f  Threshold: %.2f"
                           precision recall f1 pass-threshold))
          (println (str "Result: " (if passed? "PASS" "FAIL")))
          (println "Wrote" out-path)

          (when-not passed?
            (System/exit 1)))))))
