#!/usr/bin/env bb
;; Run Q&A challenges against an AI agent.
;; Usage: bb run-qa [options]
;;
;; Pipeline per challenge:
;;   1. Agent answers the question (README.md) → answer.md
;;   2. Extract claims from answer → claims.edn
;;   3. Score against corpus claims → scores.edn

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def project-root (str (fs/canonicalize ".")))

(def cli-spec
  {:filter    {:desc "Glob pattern to match challenge names"
               :alias :f}
   :agent     {:desc "Agent: claude or codex (default: claude)"
               :alias :a
               :default "claude"}
   :model     {:desc "Model (e.g. sonnet, opus, haiku)"
               :alias :m}
   :reasoning {:desc "Reasoning effort (e.g. low, medium, high)"
               :alias :r}
   :verbose   {:desc "Stream agent output to console"
               :alias :v
               :coerce :boolean}
   :help      {:desc "Show usage"
               :alias :h
               :coerce :boolean}})

(defn print-usage []
  (println "Usage: bb run-qa [options]")
  (println)
  (println "Options:")
  (println "  -f, --filter GLOB       Glob pattern (e.g. \"qa-depot-*\")")
  (println "  -a, --agent NAME        Agent: claude or codex (default: claude)")
  (println "  -m, --model MODEL       Model (e.g. sonnet, opus, haiku)")
  (println "  -r, --reasoning LEVEL   Reasoning effort (e.g. low, medium, high)")
  (println "  -v, --verbose           Stream agent output")
  (println "  -h, --help              Show this help"))

;;; Discover Q&A challenges

(defn qa-challenge? [dir]
  (let [edn-path (fs/path dir "challenge.edn")]
    (and (fs/exists? edn-path)
         (= :qa (:type (edn/read-string (slurp (str edn-path))))))))

(defn discover-qa-challenges []
  (->> (fs/list-dir (fs/path project-root "challenges"))
       (filter fs/directory?)
       (filter qa-challenge?)
       (map #(fs/file-name %))
       (map str)
       sort
       vec))

(defn glob->regex [glob]
  (-> glob
      (str/replace #"[.+()\[\]{}^$|\\]" "\\\\$0")
      (str/replace "*" ".*")
      (str/replace "?" ".")))

;;; Agent invocation

(defn build-agent-prompt [challenge-name]
  (let [readme (slurp (str (fs/path project-root "challenges" challenge-name "README.md")))
        answer-path (str "implementations/" challenge-name "/answer.md")]
    (str readme
         "\n\nWrite your answer to `" answer-path "`. "
         "Use any available skills and references to ensure accuracy. "
         "Answer the question precisely and completely, but do not expand scope beyond what was asked.")))

(defn claude-cmd [challenge-name model reasoning]
  (let [prompt (build-agent-prompt challenge-name)]
    (cond-> ["claude" "-p" "--verbose" "--output-format" "stream-json"
             "--dangerously-skip-permissions"
             prompt]
      model     (into ["--model" model])
      reasoning (into ["--effort" reasoning]))))

(defn codex-cmd [challenge-name model reasoning]
  (let [prompt (build-agent-prompt challenge-name)]
    (cond-> ["codex" "exec" "--json" "--dangerously-bypass-approvals-and-sandbox"
             "-C" project-root prompt]
      model     (into ["--model" model])
      reasoning (into ["--reasoning" reasoning]))))

(defn extract-text-from-stream-json
  "Extract assistant text from Claude stream-json output."
  [output]
  (let [sb (StringBuilder.)]
    (doseq [line (str/split-lines output)]
      (try
        (let [parsed (json/parse-string line true)]
          (when (= "assistant" (:type parsed))
            (doseq [block (get-in parsed [:message :content] [])]
              (when (= "text" (:type block))
                (.append sb (:text block))))))
        (catch Exception _)))
    (str sb)))

(defn extract-text-from-codex-json
  "Extract assistant text from Codex JSON output."
  [output]
  (try
    (let [parsed (json/parse-string output true)]
      (or (some (fn [item]
                  (when (= "message" (:type item))
                    (some (fn [c]
                            (when (= "output_text" (:type c))
                              (:text c)))
                          (:content item))))
                (:output parsed))
          ""))
    (catch Exception _ output)))

(defn invoke-agent! [challenge-name agent model reasoning verbose?]
  (let [cmd (case agent
              "claude" (claude-cmd challenge-name model reasoning)
              "codex"  (codex-cmd challenge-name model reasoning))
        _   (when verbose? (println "CMD:" (pr-str cmd)))
        start (System/currentTimeMillis)
        result (proc/process cmd {:dir project-root :in ""})
        out-sb (StringBuilder.)
        err-sb (StringBuilder.)
        out-fut (future
                  (with-open [rdr (io/reader (:out result))]
                    (loop []
                      (when-let [line (.readLine ^java.io.BufferedReader rdr)]
                        (.append out-sb line)
                        (.append out-sb "\n")
                        (when verbose? (println line) (flush))
                        (recur)))))
        err-fut (future
                  (with-open [rdr (io/reader (:err result))]
                    (loop []
                      (when-let [line (.readLine ^java.io.BufferedReader rdr)]
                        (.append err-sb line)
                        (.append err-sb "\n")
                        (recur)))))
        exit (:exit @result)
        _ @out-fut
        _ @err-fut
        duration-s (quot (- (System/currentTimeMillis) start) 1000)
        raw-output (str out-sb)
        ;; Read answer from the file the agent wrote
        answer-path (str (fs/path project-root "implementations" challenge-name "answer.md"))
        answer (if (fs/exists? answer-path)
                 (str/trim (slurp answer-path))
                 "")]
    {:exit exit
     :answer answer
     :raw-output raw-output
     :duration-s duration-s}))

;;; Pipeline

(defn fail-result [challenge-name duration-s]
  {:name challenge-name :status :fail :precision 0.0 :recall 0.0 :f1 0.0
   :duration-s duration-s})

(defn run-qa-challenge [challenge-name agent model reasoning verbose?]
  (println (format "\n=== Q&A: %s ===" challenge-name))
  (let [dir (fs/path project-root "challenges" challenge-name)]

    ;; Verify corpus claims exist
    (when-not (fs/exists? (fs/path dir "corpus-claims.edn"))
      (println "ERROR: corpus-claims.edn missing. Run: bb qa-extract-corpus" challenge-name)
      (System/exit 1))

    ;; Ensure implementations dir exists for the agent to write into
    (fs/create-dirs (fs/path project-root "implementations" challenge-name))

    ;; Step 1: Agent answers (writes to implementations/<name>/answer.md)
    (print "  Asking agent... ")
    (flush)
    (let [{:keys [answer duration-s]} (invoke-agent! challenge-name agent model reasoning verbose?)]
      (println (format "done (%ds, %d chars)" duration-s (count answer)))

      (if (zero? (count answer))
        (do (println "  Result: FAIL (empty answer)")
            (fail-result challenge-name duration-s))

        ;; Step 2: Extract claims
        (do
          (print "  Extracting claims... ")
          (flush)
          (let [extract-result (proc/process ["bb" "qa-extract" challenge-name]
                                             {:out :string :err :string :dir project-root})
                extract-exit (:exit @extract-result)]
            (if-not (zero? extract-exit)
              (do (println "FAILED")
                  (println "  " (str/trim (:out @extract-result)))
                  (fail-result challenge-name duration-s))

              (let [claims (edn/read-string (slurp (str (fs/path project-root "implementations" challenge-name "claims.edn"))))]
                (println (format "done (%d claims)" (count (:claims claims))))

                (if (zero? (count (:claims claims)))
                  (do (println "  Result: FAIL (no claims extracted)")
                      (fail-result challenge-name duration-s))

                  ;; Step 3: Score
                  (do
                    (print "  Scoring... ")
                    (flush)
                    (let [score-result (proc/process ["bb" "qa-score" challenge-name]
                                                     {:out :string :err :string :dir project-root})
                          _ (:exit @score-result)
                          scores-path (fs/path project-root "implementations" challenge-name "scores.edn")
                          scores (when (fs/exists? scores-path)
                                   (edn/read-string (slurp (str scores-path))))]
                      (println)
                      (if scores
                        (do
                          (println (format "  Precision: %.2f  Recall: %.2f  F1: %.2f"
                                           (:precision scores) (:recall scores) (:f1 scores)))
                          (println (format "  Result: %s (threshold: %.2f)"
                                           (if (:passed scores) "PASS" "FAIL")
                                           (:pass-threshold scores)))
                          {:name challenge-name
                           :status (if (:passed scores) :pass :fail)
                           :precision (:precision scores)
                           :recall (:recall scores)
                           :f1 (:f1 scores)
                           :duration-s duration-s})
                        (do
                          (println "  Scoring failed:" (str/trim (:out @score-result)))
                          (fail-result challenge-name duration-s))))))))))))))

;;; Results database

(def results-db-path (str (fs/path project-root ".." "qa-reports" "results.edn")))

(defn append-results!
  "Append per-challenge result maps to the results database."
  [results agent-name model reasoning]
  (let [timestamp (str (java.time.Instant/now))
        records (mapv (fn [{:keys [name status precision recall f1 duration-s]}]
                        {:timestamp  timestamp
                         :agent      agent-name
                         :model      model
                         :reasoning  reasoning
                         :challenge  name
                         :status     status
                         :precision  (or precision 0.0)
                         :recall     (or recall 0.0)
                         :f1         (or f1 0.0)
                         :duration-s (or duration-s 0)})
                      results)]
    (fs/create-dirs (fs/parent results-db-path))
    (spit results-db-path
          (str/join "\n" (map pr-str records))
          :append true)
    (spit results-db-path "\n" :append true)))

;;; Report generation

(defn generate-report
  "Generate a markdown report to ../qa-reports/. Returns the path."
  [results agent-name model reasoning]
  (let [now (java.time.LocalDateTime/now)
        date-str (.format now (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))
        time-str (.format now (java.time.format.DateTimeFormatter/ofPattern "HHmmss"))
        timestamp (.format now (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))
        reports-dir (fs/path project-root ".." "qa-reports")
        filename (cond
                   (and model reasoning) (format "%s-%s-%s-%s-%s.md" date-str time-str agent-name model reasoning)
                   model                 (format "%s-%s-%s-%s.md" date-str time-str agent-name model)
                   :else                 (format "%s-%s-%s.md" date-str time-str agent-name))
        report-path (fs/path reports-dir filename)
        passed (count (filter #(= :pass (:status %)) results))
        failed (count (filter #(= :fail (:status %)) results))
        sb (StringBuilder.)]
    (fs/create-dirs reports-dir)
    (.append sb (format "# Q&A Report - %s\n" timestamp))
    (.append sb (format "Agent: %s\n" agent-name))
    (when model (.append sb (format "Model: %s\n" model)))
    (when reasoning (.append sb (format "Reasoning: %s\n" reasoning)))
    (.append sb (format "Challenges: %d | Passed: %d | Failed: %d\n\n" (count results) passed failed))
    (.append sb "| Challenge | Status | Precision | Recall | F1 | Duration |\n")
    (.append sb "|-----------|--------|-----------|--------|----|----------|\n")
    (doseq [{:keys [name status precision recall f1 duration-s]} results]
      (.append sb (format "| %s | %s | %.2f | %.2f | %.2f | %ds |\n"
                          name
                          (if (= :pass status) "PASS" "FAIL")
                          (or precision 0.0)
                          (or recall 0.0)
                          (or f1 0.0)
                          (or duration-s 0))))
    (spit (str report-path) (str sb))
    (str report-path)))

;;; Main

(defn -main [args]
  (let [opts (cli/parse-opts args {:spec cli-spec})]
    (when (:help opts)
      (print-usage)
      (System/exit 0))

    (let [all-challenges (discover-qa-challenges)
          challenges (if-let [f (:filter opts)]
                       (let [re (re-pattern (glob->regex f))]
                         (filterv #(re-matches re %) all-challenges))
                       all-challenges)]

      (when (empty? challenges)
        (println "No Q&A challenges found.")
        (System/exit 0))

      (let [agent-name (:agent opts)
            model (:model opts)
            reasoning (:reasoning opts)]
        (println (format "Running %d Q&A challenge(s) with %s%s%s"
                         (count challenges)
                         agent-name
                         (if model (str " (" model ")") "")
                         (if reasoning (str " [" reasoning "]") "")))

        (let [results (mapv #(run-qa-challenge % agent-name model
                                               reasoning (:verbose opts))
                            challenges)
              passed (count (filter #(= :pass (:status %)) results))
              failed (count (filter #(= :fail (:status %)) results))
              report-path (generate-report results agent-name model reasoning)
              _ (append-results! results agent-name model reasoning)]

          (println)
          (println (format "=== Summary: %d passed, %d failed ===" passed failed))
          (doseq [r results]
            (println (format "  %-35s %s  F1=%.2f"
                             (:name r)
                             (if (= :pass (:status r)) "PASS" "FAIL")
                             (or (:f1 r) 0.0))))
          (println (str "Report saved: " report-path))

          (when (pos? failed)
            (System/exit 1)))))))
