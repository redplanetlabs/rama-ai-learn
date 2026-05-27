#!/usr/bin/env bb
;; Extract atomic claims from text using an LLM.
;;
;; Two modes:
;;   bb qa-extract-corpus <challenge-name>
;;     Extract claims from the corpus files for the question.
;;     Writes corpus-claims.edn (committed, human-verified).
;;
;;   bb qa-extract <challenge-name>
;;     Extract claims from the agent's answer.md.
;;     Writes claims.edn (per-run, gitignored).
;;
;; Both use the same extraction logic. The corpus extraction uses
;; the question to focus on relevant claims. The answer extraction
;; uses corpus claims as granularity examples.

(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[cheshire.core :as json])

(def project-root (str (fs/canonicalize ".")))

(defn challenge-dir [name]
  (fs/path project-root "challenges" name))

(defn read-challenge-edn [name]
  (let [path (fs/path (challenge-dir name) "challenge.edn")]
    (when-not (fs/exists? path)
      (println "ERROR: challenge.edn not found:" (str path))
      (System/exit 1))
    (edn/read-string (slurp (str path)))))

(defn read-question [name]
  "Reads the question from README.md (the full file content)."
  (let [path (fs/path (challenge-dir name) "README.md")]
    (when-not (fs/exists? path)
      (println "ERROR: README.md not found:" (str path))
      (System/exit 1))
    (str/trim (slurp (str path)))))

(defn load-corpus [corpus-refs]
  (str/join "\n\n---\n\n"
    (for [ref corpus-refs]
      (let [path (fs/path project-root ref)]
        (if (fs/exists? path)
          (slurp (str path))
          (do (println "WARNING: corpus file not found:" ref)
              ""))))))

(defn call-claude [prompt]
  (let [tmp (str (fs/create-temp-file {:prefix "qa-prompt-" :suffix ".txt"}))
        _   (spit tmp prompt)
        result (proc/shell {:out :string :err :string
                            :dir (str (fs/temp-dir))}
                           "claude" "-p"
                           "--system-prompt-file" tmp
                           "Extract the claims now. Return only the JSON array.")]
    (fs/delete tmp)
    (:out result)))

(defn parse-claims [llm-response]
  (let [trimmed (str/trim llm-response)
        json-match (re-find #"(?s)\[.*\]" trimmed)]
    (when-not json-match
      (println "ERROR: No JSON array found in LLM response")
      (println "Response was:" trimmed)
      (System/exit 1))
    (try
      (vec (json/parse-string json-match))
      (catch Exception _e
        (println "ERROR: Could not parse JSON array")
        (println "Match was:" json-match)
        (System/exit 1)))))

;;; Corpus claim extraction

(defn corpus-extraction-prompt [question corpus-text]
  (str "You are extracting atomic factual claims from documentation about Rama,\n"
       "focused on answering a specific question.\n\n"
       "Question: " question "\n\n"
       "A claim is a single, self-contained, verifiable statement.\n"
       "Extract only claims relevant to the question.\n"
       "Each claim should be a concise sentence stating one fact.\n\n"
       "Documentation:\n<corpus>\n" corpus-text "\n</corpus>\n\n"
       "Return a JSON array of claim strings. No explanation, just the array."))

(defn extract-corpus-claims [challenge-name]
  (let [challenge   (read-challenge-edn challenge-name)
        question    (read-question challenge-name)
        corpus-text (load-corpus (:corpus challenge))
        prompt      (corpus-extraction-prompt question corpus-text)
        _           (println "Extracting corpus claims for" challenge-name "...")
        response    (call-claude prompt)
        claims      (parse-claims response)
        output      {:source  (:corpus challenge)
                     :question question
                     :extracted-at (str (java.time.Instant/now))
                     :claims  claims}
        out-path    (str (fs/path (challenge-dir challenge-name) "corpus-claims.edn"))]
    (spit out-path (pr-str output))
    (println "Wrote" (count claims) "corpus claims to" out-path)
    (doseq [[i c] (map-indexed vector claims)]
      (println (str "  " (inc i) ". " c)))))

;;; Answer claim extraction

(defn answer-extraction-prompt [answer-text corpus-claims]
  (str "You are extracting atomic factual claims from a written answer about Rama.\n\n"
       "A claim is a single, self-contained, verifiable statement.\n"
       "Use the same level of granularity as these examples:\n"
       "<examples>\n"
       (str/join "\n" (map #(str "- " %) corpus-claims))
       "\n</examples>\n\n"
       "Answer to extract claims from:\n"
       "<answer>\n" answer-text "\n</answer>\n\n"
       "Return a JSON array of claim strings. No explanation, just the array."))

(defn extract-answer-claims [challenge-name]
  (let [challenge   (read-challenge-edn challenge-name)
        answer-path (fs/path project-root "implementations" challenge-name "answer.md")
        _           (when-not (fs/exists? answer-path)
                      (println "ERROR: answer.md not found:" (str answer-path))
                      (System/exit 1))
        answer-text (slurp (str answer-path))
        corpus-path (fs/path (challenge-dir challenge-name) "corpus-claims.edn")
        _           (when-not (fs/exists? corpus-path)
                      (println "ERROR: corpus-claims.edn not found. Run bb qa-extract-corpus first.")
                      (System/exit 1))
        corpus-data (edn/read-string (slurp (str corpus-path)))
        prompt      (answer-extraction-prompt answer-text (:claims corpus-data))
        _           (println "Extracting answer claims for" challenge-name "...")
        response    (call-claude prompt)
        claims      (parse-claims response)
        output      {:source-file  "answer.md"
                     :extracted-at (str (java.time.Instant/now))
                     :claims       claims}
        impl-dir    (fs/path project-root "implementations" challenge-name)
        _           (fs/create-dirs impl-dir)
        out-path    (str (fs/path impl-dir "claims.edn"))]
    (spit out-path (pr-str output))
    (println "Wrote" (count claims) "answer claims to" out-path)
    (doseq [[i c] (map-indexed vector claims)]
      (println (str "  " (inc i) ". " c)))))

;;; Entry points (set by bb.edn task name via *extract-mode*)

(defn -main [args]
  (when (empty? args)
    (println (str "Usage: bb " (if (= *extract-mode* :corpus)
                                 "qa-extract-corpus"
                                 "qa-extract")
                  " <challenge-name>"))
    (System/exit 1))
  (if (= *extract-mode* :corpus)
    (extract-corpus-claims (first args))
    (extract-answer-claims (first args))))
