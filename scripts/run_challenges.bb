#!/usr/bin/env bb

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[babashka.tasks :as tasks])

;; Load encryption helpers — provides encrypt-challenge! / decrypt-challenge! / derive-key
(load-file (str (fs/parent (fs/absolutize *file*)) "/encrypt_challenges.bb"))

(def cli-spec
  {:filter     {:desc "Glob pattern to match challenge names (e.g. \"basic-*\")"
                :alias :f}
   :batch      {:desc "Batch number to run (1-5)"
                :alias :b
                :coerce :int}
   :difficulty {:desc "Difficulty filter: standard or hard"
                :alias :d}
   :agent      {:desc "Agent to use: claude or codex (default: claude)"
                :alias :a
                :default "claude"}
   :model      {:desc "Model to use (e.g. sonnet, opus, haiku)"
                :alias :m}
   :verbose    {:desc "Stream agent output to console in real time"
                :alias :v
                :coerce :boolean}
   :reasoning  {:desc "Reasoning effort level (e.g. low, medium, high)"
                :alias :r}
   :pretty     {:desc "Pretty-print agent output (implies --verbose)"
                :alias :p
                :coerce :boolean}
   :help       {:desc "Show usage"
                :alias :h
                :coerce :boolean}})

(defn parse-challenge-order
  "Parse CHALLENGE_ORDER.md into a vector of challenge maps.
  Returns [{:name \"simple-streaming\" :batch 1 :difficulty :standard} ...]"
  [path]
  (let [lines (str/split-lines (slurp path))]
    (loop [lines lines
           batch nil
           result []]
      (if-let [line (first lines)]
        (let [batch-match (re-matches #"## Batch (\d+):.*" line)
              challenge-match (re-matches #"- (.+)" (str/trim line))]
          (cond
            batch-match
            (recur (rest lines) (parse-long (second batch-match)) result)

            (and challenge-match batch)
            (let [name (second challenge-match)
                  difficulty (if (str/ends-with? name "-hard") :hard :standard)]
              (recur (rest lines) batch (conj result {:name name
                                                      :batch batch
                                                      :difficulty difficulty})))

            :else
            (recur (rest lines) batch result)))
        result))))

(defn glob->regex
  "Convert a simple glob pattern to a regex pattern.
  Supports * (any chars) and ? (single char)."
  [glob]
  (-> glob
      (str/replace "\\" "\\\\")
      (str/replace #"[.+()\[\]{}^$|]" "\\\\$0")
      (str/replace "*" ".*")
      (str/replace "?" ".")))

(defn filter-challenges
  "Apply CLI filters to a challenge list. Filters compose with AND logic."
  [challenges {:keys [filter batch difficulty]}]
  (cond->> challenges
    filter     (filterv #(re-matches (re-pattern (glob->regex filter)) (:name %)))
    batch      (filterv #(= batch (:batch %)))
    difficulty (filterv #(= (keyword difficulty) (:difficulty %)))))

(defn validate-challenges
  "Check that each challenge has a directory with README.md. Returns
  {:valid [...] :missing [...]}."
  [challenges project-root]
  (let [grouped (group-by
                 (fn [{:keys [name]}]
                   (if (fs/exists? (fs/path project-root "challenges" name "README.md"))
                     :valid
                     :missing))
                 challenges)]
    {:valid (vec (:valid grouped))
     :missing (vec (:missing grouped))}))

(defn discover-all-challenges
  "Discover all runnable challenges from challenges/*/README.md, excluding
  non-runnable template/shared directories. Returns challenge maps with the
  same shape as parse-challenge-order, filling missing metadata when needed."
  [project-root ordered-challenges]
  (let [ordered-by-name (into {} (map (juxt :name identity) ordered-challenges))]
    (->> (fs/list-dir (fs/path project-root "challenges"))
         (filter fs/directory?)
         (map #(fs/file-name %))
         (map str)
         (filter #(fs/exists? (fs/path project-root "challenges" % "README.md")))
         sort
         (mapv (fn [name]
                 (or (get ordered-by-name name)
                     {:name name
                      :batch nil
                      :difficulty (if (str/ends-with? name "-hard") :hard :standard)}))))))

(defn print-usage []
  (println "Usage: bb run-challenges [options]")
  (println)
  (println "Options:")
  (println "  -f, --filter GLOB       Glob pattern to match challenge names (e.g. \"basic-*\")")
  (println "  -b, --batch N           Batch number to run (1-5)")
  (println "  -d, --difficulty TYPE   Difficulty filter: standard or hard")
  (println "  -a, --agent NAME        Agent to use: claude or codex (default: claude)")
  (println "  -m, --model MODEL       Model to use (e.g. sonnet, opus, haiku)")
  (println "  -r, --reasoning LEVEL   Reasoning effort level (e.g. low, medium, high)")
  (println "  -v, --verbose           Stream agent output to console in real time")
  (println "  -h, --help              Show this help")
  (println)
  (println "Note: Batch 5 (cluster operations) requires a running local Rama cluster.")
  (println "      Set RAMA_CONDUCTOR_HOST/RAMA_CONDUCTOR_UI_PORT to override defaults.")
  (println "      Batch 5 challenges are skipped gracefully when the cluster is unavailable."))

;;; Cluster precondition

(def cluster-batch 5)

(defn cluster-running?
  "Check if the Rama conductor is reachable. Returns true when the
  conductor HTTP port responds, false otherwise."
  []
  (let [host (or (System/getenv "RAMA_CONDUCTOR_HOST") "localhost")
        port (or (some-> (System/getenv "RAMA_CONDUCTOR_UI_PORT") parse-long) 8888)
        url  (str "http://" host ":" port "/")]
    (try
      (let [client  (java.net.http.HttpClient/newHttpClient)
            request (-> (java.net.http.HttpRequest/newBuilder)
                        (.uri (java.net.URI/create url))
                        (.timeout (java.time.Duration/ofSeconds 3))
                        (.GET)
                        (.build))
            resp    (.send client request (java.net.http.HttpResponse$BodyHandlers/discarding))]
        (< (.statusCode resp) 500))
      (catch Exception _ false))))

(defn partition-by-cluster
  "Split challenges into {:local [...] :cluster [...]} by batch number."
  [challenges]
  (group-by (fn [{:keys [batch]}]
              (if (= batch cluster-batch) :cluster :local))
            challenges))

;;; Agent abstraction

(def ^:private allowed-tools
  "Tools the agent is allowed to use during challenge runs."
  "Read,Write,Edit,Glob,Grep,Bash,Skill")

(defn claude-phase-cmd
  "Build the CLI command to invoke Claude for a single phase of a challenge."
  [challenge-name phase-id _project-root model reasoning]
  (cond-> ["claude" "--print" "--output-format" "stream-json" "--verbose"
           "--allowedTools" allowed-tools
           "-p" (str "/challenge-phase " challenge-name " " phase-id)]
    model     (into ["--model" model])
    reasoning (into ["--effort" reasoning])))

(defn codex-phase-cmd
  "Build the CLI command to invoke Codex for a single phase of a challenge.
  Note: requires a $challenge-phase command in the codex skills setup."
  [challenge-name phase-id project-root model reasoning]
  (cond-> ["codex" "exec" "--json" "--dangerously-bypass-approvals-and-sandbox"
           "-C" project-root]
    model     (into ["--model" model])
    reasoning (into ["-c" (str "model_reasoning_effort=" reasoning)])
    true      (conj (str "$challenge-phase " challenge-name " " phase-id))))

(def agents
  {:claude {:phase-cmd claude-phase-cmd}
   :codex  {:phase-cmd codex-phase-cmd}})

;;; Pricing

(def claude-model-pricing
  "USD cost per million tokens for Claude models.
  Ordered list of [substring pricing-map] pairs matched against model names."
  [["haiku"  {:input 0.80  :output 4.00  :cache-write 1.00  :cache-read 0.08}]
   ["sonnet" {:input 3.00  :output 15.00 :cache-write 3.75  :cache-read 0.30}]
   ["opus"   {:input 15.00 :output 75.00 :cache-write 18.75 :cache-read 1.50}]])

(defn model->pricing
  "Return pricing map for a model name string, or nil if unknown."
  [model]
  (when model
    (some (fn [[k v]] (when (str/includes? (str/lower-case model) k) v))
          claude-model-pricing)))

(defn compute-cost
  "Calculate USD cost from token usage and a pricing map.
  Returns nil when pricing is unavailable."
  [{:keys [input-tokens output-tokens cache-creation-tokens cache-read-tokens]} pricing]
  (when pricing
    (+ (* (or input-tokens 0) (/ (:input pricing) 1e6))
       (* (or output-tokens 0) (/ (:output pricing) 1e6))
       (* (or cache-creation-tokens 0) (/ (:cache-write pricing) 1e6))
       (* (or cache-read-tokens 0) (/ (:cache-read pricing) 1e6)))))

(defn format-cost
  "Format a cost value as a dollar string, or \"N/A\" when nil."
  [cost]
  (if cost (format "$%.4f" cost) "N/A"))

;;; Encryption

(defn challenge-encryption-key
  "Return a derived AES key from the CHALLENGE_KEY env var.
  Exits with an error if CHALLENGE_KEY is not set."
  []
  (let [k (or (System/getenv "CHALLENGE_KEY")
              (do (println "Error: CHALLENGE_KEY env var is not set.")
                  (println "Challenge encryption is required. Set CHALLENGE_KEY to a passphrase.")
                  (System/exit 1)))]
    (derive-key k)))

;;; Implementation cleaning

(defn clean-implementation-dir!
  "Remove the entire implementation directory for a challenge."
  [project-root challenge-name]
  (let [impl-dir (fs/path project-root "implementations" challenge-name)]
    (when (fs/exists? impl-dir)
      (fs/delete-tree impl-dir))))

;;; Output parsing

(defn parse-iterations
  "Parse the number of test attempts from agent output.
  Prefers structured CHALLENGE_RESULT line, falls back to heuristics."
  [output]
  (if-let [m (re-find #"CHALLENGE_RESULT:.*iterations=(\d+)" output)]
    (parse-long (second m))
    (let [attempt-matches (re-seq #"(?i)attempt[s]?\s+(\d+)" output)]
      (if (seq attempt-matches)
        (apply max (map #(parse-long (second %)) attempt-matches))
        1))))

(defn parse-pass-fail
  "Determine pass/fail from agent exit code and output.
  Prefers structured CHALLENGE_RESULT line, falls back to heuristics."
  [exit-code output]
  (if-let [m (re-find #"CHALLENGE_RESULT:status=(pass|fail)" output)]
    (keyword (second m))
    (cond
      (not= 0 exit-code) :fail
      (re-find #"(?i)tests?\s+pass" output) :pass
      (re-find #"(?i)report success" output) :pass
      (re-find #"(?i)all \d+ tests? (passed|pass)" output) :pass
      (re-find #"0 failures, 0 errors" output) :pass
      (re-find #"(?i)attempts?\s*>=?\s*5" output) :fail
      (re-find #"(?i)stop and report the failure" output) :fail
      ;; If exit code is 0 and no clear failure signal, assume pass
      :else :pass)))

(defn parse-phase-verdict
  "Extract PHASE_VALIDATION verdict from agent output. Returns one of
  :pass, :fail, :minor-fail, :major-fail, or nil if no verdict line is present.
  Phase 2 emits pass/fail. Phases 4 and 6 emit pass/minor-fail/major-fail.
  Phase 7 (finish) emits pass/fail.

  Returns the LAST verdict match in the output. The agent's output (stream-json)
  includes the phase doc's verdict instructions in earlier tool_result events,
  which contain literal PHASE_VALIDATION:pass/fail text. Only the final emitted
  verdict matters, so we take the last occurrence."
  [output]
  (when-let [matches (seq (re-seq #"PHASE_VALIDATION:(minor-fail|major-fail|pass|fail)" output))]
    (keyword (second (last matches)))))

(def score-keys [:alignment :test-alignment])

(defn parse-skills-used
  "Extract distinct skill names from agent NDJSON output.
  Handles Claude (Skill tool_use in assistant messages) and
  Codex (command_execution reading SKILL.md files from skills directories)."
  [output]
  (let [skills (reduce
                (fn [acc line]
                  (try
                    (let [parsed (json/parse-string line true)]
                      (cond
                        ;; Claude: assistant event with Skill tool_use
                        (= "assistant" (:type parsed))
                        (reduce (fn [acc2 block]
                                  (if (and (= "tool_use" (:type block))
                                           (= "Skill" (:name block)))
                                    (conj acc2 (get-in block [:input :skill]))
                                    acc2))
                                acc
                                (get-in parsed [:message :content] []))

                        ;; Codex: command_execution reading a SKILL.md file
                        (and (= "item.started" (:type parsed))
                             (= "command_execution" (get-in parsed [:item :type])))
                        (let [cmd (get-in parsed [:item :command] "")]
                          (if-let [matches (re-seq #"(?:skills|plugins)/(?:[^/]+/skills/)?([^/]+)/SKILL\.md" cmd)]
                            (into acc (map second matches))
                            acc))

                        :else acc))
                    (catch Exception _ acc)))
                #{}
                (remove str/blank? (str/split-lines (or output ""))))]
    (vec (sort skills))))

(defn parse-skill-refs-used
  "Extract distinct skill reference filenames accessed by the agent.
  Detects Read/Glob/Grep tool calls whose paths contain references/*.md,
  and Codex command_execution events reading reference files."
  [output]
  (let [refs (reduce
              (fn [acc line]
                (try
                  (let [parsed (json/parse-string line true)]
                    (cond
                      ;; Claude: assistant event with tool_use blocks
                      (= "assistant" (:type parsed))
                      (reduce (fn [acc2 block]
                                (if (= "tool_use" (:type block))
                                  (let [input (json/generate-string (or (:input block) {}))
                                        matches (re-seq #"references/([a-z_-]+\.md)" input)]
                                    (into acc2 (map second matches)))
                                  acc2))
                              acc
                              (get-in parsed [:message :content] []))

                      ;; Codex: command_execution reading a reference file
                      (and (= "item.started" (:type parsed))
                           (= "command_execution" (get-in parsed [:item :type])))
                      (let [cmd (get-in parsed [:item :command] "")]
                        (if-let [matches (re-seq #"references/([a-z_-]+\.md)" cmd)]
                          (into acc (map second matches))
                          acc))

                      :else acc))
                  (catch Exception _ acc)))
              #{}
              (remove str/blank? (str/split-lines (or output ""))))]
    (vec (sort refs))))

(defn parse-tool-uses
  "Count tool invocations from agent NDJSON output.
  Handles Claude (assistant events with tool_use content blocks) and
  Codex (item.started with item.type=command_execution) event formats."
  [output]
  (reduce
   (fn [acc line]
     (try
       (let [parsed (json/parse-string line true)]
         (cond
           ;; Claude: assistant event with tool_use items in message.content
           (= "assistant" (:type parsed))
           (+ acc (count (filter #(= "tool_use" (:type %))
                                 (get-in parsed [:message :content] []))))
           ;; Codex: item.started with command_execution item
           (and (= "item.started" (:type parsed))
                (= "command_execution" (get-in parsed [:item :type])))
           (inc acc)
           :else acc))
       (catch Exception _ acc)))
   0
   (remove str/blank? (str/split-lines (or output "")))))

(defn extract-usage-from-result
  "Extract token usage from a parsed JSON event map.
  Handles Claude's result-type events and Codex's turn.completed events.
  Returns a usage map or nil if the event has no usage."
  [parsed]
  (when-let [usage (:usage parsed)]
    (condp = (:type parsed)
      ;; Claude: {"type":"result","usage":{"input_tokens":...,"cache_creation_input_tokens":...,...}}
      "result"
      {:input-tokens          (get usage :input_tokens 0)
       :output-tokens         (get usage :output_tokens 0)
       :cache-creation-tokens (get usage :cache_creation_input_tokens 0)
       :cache-read-tokens     (get usage :cache_read_input_tokens 0)}
      ;; Codex: {"type":"turn.completed","usage":{"input_tokens":...,"cached_input_tokens":...,...}}
      "turn.completed"
      {:input-tokens          (get usage :input_tokens 0)
       :output-tokens         (get usage :output_tokens 0)
       :cache-creation-tokens 0
       :cache-read-tokens     (get usage :cached_input_tokens 0)}
      nil)))

(defn parse-token-usage
  "Parse token usage from Claude's JSON or NDJSON output.
  Handles both NDJSON (one JSON object per line) and a single JSON object.
  Sums usage fields across all result-type messages. Returns a map with
  :input-tokens, :output-tokens, :cache-creation-tokens, :cache-read-tokens."
  [output]
  (let [zero-usage {:input-tokens 0
                    :output-tokens 0
                    :cache-creation-tokens 0
                    :cache-read-tokens 0}
        add-usage  (fn [acc u]
                     (-> acc
                         (update :input-tokens + (:input-tokens u))
                         (update :output-tokens + (:output-tokens u))
                         (update :cache-creation-tokens + (:cache-creation-tokens u))
                         (update :cache-read-tokens + (:cache-read-tokens u))))
        ;; First try NDJSON: process each line independently
        ndjson-result (let [lines (remove str/blank? (str/split-lines (or output "")))]
                        (reduce
                         (fn [acc line]
                           (try
                             (let [parsed (json/parse-string line true)]
                               (if-let [u (extract-usage-from-result parsed)]
                                 (add-usage acc u)
                                 acc))
                             (catch Exception _ acc)))
                         zero-usage
                         lines))]
    (if (not= zero-usage ndjson-result)
      ;; NDJSON parsing found tokens
      ndjson-result
      ;; Fall back: try parsing the entire output as a single multi-line JSON object
      (or (try
            (let [parsed (json/parse-string (str/trim (or output "")) true)]
              (when-let [u (extract-usage-from-result parsed)]
                (add-usage zero-usage u)))
            (catch Exception _ nil))
          zero-usage))))

(defn token-totals
  "Sum token usage across a sequence of result maps.
  Returns a map with :input-tokens, :output-tokens, :cache-creation-tokens, :cache-read-tokens."
  [results]
  {:input-tokens (reduce + 0 (map #(or (:input-tokens %) 0) results))
   :output-tokens (reduce + 0 (map #(or (:output-tokens %) 0) results))
   :cache-creation-tokens (reduce + 0 (map #(or (:cache-creation-tokens %) 0) results))
   :cache-read-tokens (reduce + 0 (map #(or (:cache-read-tokens %) 0) results))})

;;; Transcript saving

(defn save-transcript!
  "Save JSONL agent output to ../transcripts relative to project-root.
  Filename: {date}-{time}-{agent}[-{model}][-{reasoning}]-{challenge}-phase{N}[-attempt{K}].jsonl
  All transcripts of one challenge run share the same {date}-{time} prefix
  (the run-start-time), so they can be grouped as a unit. Returns the path written."
  [project-root agent-name model reasoning challenge-name content
   phase-id attempt run-start-time]
  (let [t               (or run-start-time (java.time.LocalDateTime/now))
        date-str        (.format t (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))
        time-str        (.format t (java.time.format.DateTimeFormatter/ofPattern "HHmmss"))
        transcripts-dir (fs/path project-root ".." "transcripts")
        base            (cond
                          (and model reasoning) (format "%s-%s-%s-%s-%s" date-str time-str agent-name model reasoning)
                          model                 (format "%s-%s-%s-%s" date-str time-str agent-name model)
                          :else                 (format "%s-%s-%s" date-str time-str agent-name))
        phase-suffix    (cond
                          (and phase-id (> attempt 1)) (format "-phase%d-attempt%d" phase-id attempt)
                          phase-id                     (format "-phase%d" phase-id)
                          :else                        "")
        filename        (str base "-" challenge-name phase-suffix ".jsonl")
        path            (str (fs/path transcripts-dir filename))]
    (fs/create-dirs transcripts-dir)
    (spit path content)
    path))

;;; Core runner

(def ^:dynamic *outer-timeout-s*
  "Outer timeout for a single subprocess invocation (seconds).
  Phase invocations bind this to min(default, time-remaining-in-overall-budget)
  so a single phase can't exceed either the per-call cap or the run-wide cap."
  (* 2 3600))

(def ^:dynamic *overall-timeout-s*
  "Hard cap on total wall-clock for one challenge run (seconds).
  Includes all phase invocations, retries, lint, and test runs."
  (* 4 3600))

(defn time-remaining-s
  "Seconds left in the overall challenge run budget. Never negative."
  [run-start-millis]
  (let [elapsed (/ (- (System/currentTimeMillis) run-start-millis) 1000.0)]
    (max 0 (- *overall-timeout-s* elapsed))))

(defn stream-and-capture
  "Read an InputStream line-by-line, printing each line to print-fn
  and accumulating into a StringBuilder. Returns the captured string."
  [^java.io.InputStream input-stream print-fn]
  (let [sb (StringBuilder.)]
    (with-open [rdr (io/reader input-stream)]
      (loop []
        (when-let [line (.readLine ^java.io.BufferedReader rdr)]
          (.append sb line)
          (.append sb "\n")
          (print-fn line)
          (recur))))
    (str sb)))

(def ^:dynamic *verbose* false)

(def ^:dynamic *pretty* false)

;;; Pretty-printing stream-json output

(def ^:private BOLD "\033[1m")
(def ^:private DIM "\033[2m")
(def ^:private GREEN "\033[32m")
(def ^:private YELLOW "\033[33m")
(def ^:private CYAN "\033[36m")
(def ^:private RED "\033[31m")
(def ^:private RESET "\033[0m")

(defn- pretty-separator [label]
  (let [ts (.format (java.time.LocalDateTime/now)
                    (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))]
    (str "\n" DIM "─── " RESET BOLD label RESET DIM " ─── " ts " ───" RESET)))

(defn- truncate-lines [s max-lines]
  (when s
    (let [lines (str/split-lines s)
          n (count lines)]
      (if (<= n max-lines)
        s
        (str (str/join "\n" (take max-lines lines))
             "\n" DIM "  ... (" (- n max-lines) " more lines)" RESET)))))

(defn- head-tail-lines [s head-n tail-n]
  (when s
    (let [lines (str/split-lines s)
          n (count lines)
          total (+ head-n tail-n)]
      (if (<= n total)
        s
        (str (str/join "\n" (take head-n lines))
             "\n" DIM "  ... (skipped " (- n total) " lines)" RESET "\n"
             (str/join "\n" (take-last tail-n lines)))))))

(defn- pretty-tool-call [{:keys [name input]}]
  (println (pretty-separator (str CYAN "Tool: " name RESET)))
  (case name
    "Bash"
    (do (println (str GREEN "$ " RESET (:command input)))
        (when (:description input)
          (println (str DIM "  # " (:description input) RESET))))

    "Edit"
    (do (println (str YELLOW (:file_path input) RESET))
        (println (str DIM "old:" RESET))
        (println (truncate-lines (:old_string input) 20))
        (println (str DIM "new:" RESET))
        (println (truncate-lines (:new_string input) 20)))

    "Write"
    (do (println (str YELLOW (:file_path input) RESET))
        (println (truncate-lines (:content input) 40)))

    "Read"
    (println (str YELLOW (:file_path input) RESET
                  (when (:offset input) (str " (offset=" (:offset input) ")"))
                  (when (:limit input) (str " (limit=" (:limit input) ")"))))

    "Glob"
    (println (str (:pattern input)
                  (when (:path input) (str " in " (:path input)))))

    "Grep"
    (println (str "/" (:pattern input) "/"
                  (when (:glob input) (str " --glob " (:glob input)))
                  (when (:path input) (str " in " (:path input)))))

    "Skill"
    (println (str BOLD (:skill input) RESET
                  (when (:args input) (str " " (:args input)))))

    "TodoWrite"
    (doseq [todo (:todos input)]
      (println (str (case (:status todo)
                      "completed" (str GREEN "✓" RESET)
                      "in_progress" (str YELLOW "→" RESET)
                      " ")
                    " " (:content todo))))

    ;; default: skip noisy tools like Glob results
    nil))

(defn- pretty-tool-result [content]
  (when (sequential? content)
    (doseq [item content]
      (when (= "tool_result" (:type item))
        (let [result (:content item)
              error? (:is_error item)]
          (when (and (string? result) (not (str/blank? result)))
            (if error?
              (println (str RED (truncate-lines result 30) RESET))
              (println (str DIM (truncate-lines result 30) RESET)))))))))

(defn- pretty-print-line
  "Parse a stream-json line and print a human-readable version."
  [line]
  (try
    (let [event (json/parse-string line true)]
      (case (:type event)
        "system"
        (when (= "init" (:subtype event))
          (println (pretty-separator "Session"))
          (println (str "Model: " BOLD (:model event) RESET
                        "  Mode: " (:permissionMode event))))

        "assistant"
        (let [content (-> event :message :content)]
          (when (sequential? content)
            (doseq [item content]
              (case (:type item)
                "text" (when (and (:text item) (not (str/blank? (:text item))))
                         (println (:text item)))
                "thinking" (when (and (:thinking item) (not (str/blank? (:thinking item))))
                             (println (pretty-separator (str DIM "Thinking" RESET)))
                             (println (str DIM (head-tail-lines (:thinking item) 100 100) RESET)))
                "tool_use" (pretty-tool-call item)
                nil))))

        "user"
        (let [content (-> event :message :content)]
          (when (sequential? content)
            (doseq [item content]
              (when (and (= "text" (:type item)) (:text item) (not (str/blank? (:text item))))
                (println (str DIM (truncate-lines (:text item) 10) RESET)))))
          (pretty-tool-result content))

        "result"
        (do (println (pretty-separator "Result"))
            (println (str (if (:is_error event) RED GREEN)
                          (:result event) RESET)))

        nil))
    (catch Exception _ nil))
  (flush))

(defn invoke-command!
  "Run a shell command, capturing stdout+stderr and wall-clock duration.
  In verbose mode, streams output to console in real time.
  Always captures partial output on timeout so transcripts are preserved.
  Returns {:exit int, :out string, :err string, :duration-s int}."
  [cmd project-root]
  (when *verbose*
    (prn "CMD:" cmd)
    (flush))
  (let [start (System/currentTimeMillis)
        result (try
                 ;; Always stream via futures so partial output is captured on timeout
                 (let [proc (p/process cmd {:dir project-root :in ""
                                            :extra-env {"CHALLENGE_KEY" nil}})
                       out-fut (future
                                 (stream-and-capture
                                  (:out proc)
                                  (fn [line]
                                    (when *verbose*
                                      (if *pretty*
                                        (pretty-print-line line)
                                        (do (println line) (flush)))))))
                       err-fut (future
                                 (stream-and-capture
                                  (:err proc)
                                  (fn [line]
                                    (when *verbose*
                                      (binding [*out* *err*]
                                        (println line)
                                        (flush))))))
                       done (deref proc
                              (* *outer-timeout-s* 1000)
                              :timeout)]
                   (if (= done :timeout)
                     (do (.destroyForcibly (:proc proc))
                         {:exit 1
                          :timed-out? true
                          :out (str @out-fut)
                          :err (str "Timeout after " *outer-timeout-s* "s\n" @err-fut)})
                     {:exit (:exit done)
                      :out @out-fut
                      :err @err-fut}))
                 (catch Exception e
                   {:exit 1
                    :out ""
                    :err (str "Process error: " (.getMessage e))}))
        duration-s (quot (- (System/currentTimeMillis) start) 1000)]
    (assoc result :duration-s duration-s)))

;;; Challenge scoring

(defn compute-challenge-score
  "Compute challenge score: 0 if failed, otherwise max(1, round(100 / 2^(iterations-1)))."
  [status private-status iterations]
  (if (or (not= :pass status)
          (= :fail private-status))
    0
    (max 1 (Math/round (/ 100.0 (Math/pow 2 (dec iterations)))))))

;;; Alignment scoring

(defn find-impl-files
  "Find all .clj files in the agent's implementation src directory."
  [project-root challenge-name]
  (let [src-dir (fs/path project-root "implementations" challenge-name "src")]
    (when (fs/exists? src-dir)
      (vec (filter #(str/ends-with? (str %) ".clj") (fs/glob src-dir "**"))))))

(defn find-ref-files
  "Find all .clj files in the challenge's test-resources directory."
  [project-root challenge-name]
  (let [ref-dir (fs/path project-root "challenges" challenge-name "test-resources")]
    (when (fs/exists? ref-dir)
      (vec (filter #(str/ends-with? (str %) ".clj") (fs/glob ref-dir "**"))))))

(defn build-alignment-prompt
  "Build the prompt for alignment scoring."
  [project-root challenge-name]
  (let [rubric    (slurp (str (fs/path project-root "SCORING_RUBRIC.md")))
        impl-files (find-impl-files project-root challenge-name)
        ref-files  (find-ref-files project-root challenge-name)]
    (when (seq impl-files)
      (let [impl-code (str/join "\n\n" (map #(str "--- " (fs/file-name %) " ---\n" (slurp (str %)))
                                            impl-files))
            ref-code  (when (seq ref-files)
                        (str/join "\n\n" (map #(str "--- " (fs/file-name %) " ---\n" (slurp (str %)))
                                              ref-files)))]
        (str "Score the structural alignment of an agent's implementation against a reference implementation.\n\n"
             "## Scoring Rubric\n\n" rubric "\n\n"
             "## Reference Implementation\n\n"
             (if ref-code
               (str "```clojure\n" ref-code "\n```")
               "NOT AVAILABLE — score 0 for alignment.")
             "\n\n## Agent Implementation\n\n"
             "```clojure\n" impl-code "\n```\n\n"
             "Respond with EXACTLY one line in this format:\n"
             "ALIGNMENT_SCORE:<score>\n"
             "where <score> is an integer 0-5. Score 0 if the reference implementation is not available above.\n"
             "Follow the score line with a one-sentence justification.")))))

(defn run-alignment-scoring!
  "Run alignment scoring as a separate LLM call. Returns {:alignment int} or nil."
  [project-root challenge-name model]
  (when-let [prompt (build-alignment-prompt project-root challenge-name)]
    (let [cmd  (cond-> ["claude" "--print"]
                 model (into ["--model" model]))
          start (System/currentTimeMillis)
          proc (p/process cmd {:dir project-root :in prompt})
          out-fut (future (slurp (:out proc)))
          err-fut (future (slurp (:err proc)))
          done @proc
          output @out-fut
          err-output @err-fut
          duration-s (quot (- (System/currentTimeMillis) start) 1000)]
      (when *verbose*
        (println (format "Alignment scoring for %s (%ds)" challenge-name duration-s)))
      (when (not= 0 (:exit done))
        (binding [*out* *err*]
          (println (format "WARN: alignment scoring failed for %s (exit %d)" challenge-name (:exit done)))
          (when (seq (str/trim err-output))
            (println (str "  stderr: " (str/trim err-output))))))
      (when-let [[_ score-str] (re-find #"ALIGNMENT_SCORE:(\d+)" output)]
        (let [score (parse-long score-str)
              ;; Grab everything after the ALIGNMENT_SCORE line as justification
              justification (some-> (re-find #"ALIGNMENT_SCORE:\d+\s*\n(.*)" output)
                                    second
                                    str/trim
                                    not-empty)]
          (when (<= 0 score 5)
            (when (and *verbose* justification)
              (println (format "  Alignment %d/5: %s" score justification)))
            (cond-> {:alignment score}
              justification (assoc :alignment-justification justification))))))))

;;; Test-coverage alignment scoring

(defn find-agent-test-files
  "Find all .clj files in the agent's implementation test directory."
  [project-root challenge-name]
  (let [test-dir (fs/path project-root "implementations" challenge-name "test")]
    (when (fs/exists? test-dir)
      (vec (filter #(str/ends-with? (str %) ".clj") (fs/glob test-dir "**"))))))

(defn find-functional-test-files
  "Find functional private test files (functional_test_support.clj) for a challenge."
  [project-root challenge-name]
  (let [priv-dir (fs/path project-root "challenges" challenge-name "test-private")]
    (when (fs/exists? priv-dir)
      (vec (filter #(str/includes? (str (fs/file-name %)) "functional")
                   (filter #(str/ends-with? (str %) ".clj") (fs/glob priv-dir "**")))))))

(defn build-test-alignment-prompt
  "Build the prompt for test-coverage alignment scoring."
  [project-root challenge-name]
  (let [agent-tests (find-agent-test-files project-root challenge-name)
        func-tests  (find-functional-test-files project-root challenge-name)]
    (when (and (seq agent-tests) (seq func-tests))
      (let [agent-code (str/join "\n\n" (map #(str "--- " (fs/file-name %) " ---\n" (slurp (str %)))
                                              agent-tests))
            func-code  (str/join "\n\n" (map #(str "--- " (fs/file-name %) " ---\n" (slurp (str %)))
                                              func-tests))]
        (str "Score how well an agent's self-written tests cover the same behaviors as a reference functional test suite.\n\n"
             "## Scoring Rubric\n\n"
             "### Efficiency penalty\n"
             "Count the number of `create-ipc` calls and `launch-module!` calls in both the agent tests and reference tests. "
             "If the agent tests create more IPC instances or launch more modules than the reference tests, reduce the score by 1 (minimum 1).\n\n"
             "If the number of tasks is not randomized for module launch, reduce the score by 1 (minimum 1).\n\n"
             "If the number of workers is not more than one, reduce the score by 1 (minimum 1).\n\n"
             "### Coverage scoring\n"
             "| Score | Anchor |\n"
             "|-------|--------|\n"
             "| 5 | Agent tests cover all behaviors in the reference tests, and may have additional coverage. |\n"
             "| 4 | Agent tests cover nearly all reference behaviors, missing only minor edge cases. |\n"
             "| 3 | Agent tests cover the core happy-path behaviors but miss several edge cases or failure scenarios. |\n"
             "| 2 | Agent tests cover some behaviors but miss significant categories (e.g., all failure cases, or an entire protocol method). |\n"
             "| 1 | Agent tests are minimal — only trivial smoke tests with little meaningful coverage. |\n\n"
             "## Reference Functional Tests\n\n"
             "```clojure\n" func-code "\n```\n\n"
             "## Agent-Written Tests\n\n"
             "```clojure\n" agent-code "\n```\n\n"
             "Respond with EXACTLY one line in this format:\n"
             "TEST_ALIGNMENT_SCORE:<score>\n"
             "where <score> is an integer 1-5.\n"
             "Follow the score line with a one-sentence justification.")))))

(defn agent-tests-use-harness?
  "Check if any agent test file references rama-challenges.harness."
  [project-root challenge-name]
  (let [test-files (find-agent-test-files project-root challenge-name)]
    (some (fn [f]
            (let [content (slurp (str f))]
              (re-find #"rama-challenges\.harness" content)))
          test-files)))

(defn run-test-alignment-scoring!
  "Run test-coverage alignment scoring. Returns {:test-alignment int} or nil."
  [project-root challenge-name model]
  (if (agent-tests-use-harness? project-root challenge-name)
    (do (when *verbose*
          (println (format "  Test alignment 1/5: agent tests use rama-challenges.harness")))
        {:test-alignment 1
         :test-alignment-justification "Agent tests use rama-challenges.harness (automatic score of 1)."})
    (when-let [prompt (build-test-alignment-prompt project-root challenge-name)]
      (let [cmd  (cond-> ["claude" "--print"]
                   model (into ["--model" model]))
            start (System/currentTimeMillis)
            proc (p/process cmd {:dir project-root :in prompt})
            out-fut (future (slurp (:out proc)))
            err-fut (future (slurp (:err proc)))
            done @proc
            output @out-fut
            err-output @err-fut
            duration-s (quot (- (System/currentTimeMillis) start) 1000)]
        (when *verbose*
          (println (format "Test alignment scoring for %s (%ds)" challenge-name duration-s)))
        (when (not= 0 (:exit done))
          (binding [*out* *err*]
            (println (format "WARN: test alignment scoring failed for %s (exit %d)" challenge-name (:exit done)))
            (when (seq (str/trim err-output))
              (println (str "  stderr: " (str/trim err-output))))))
        (when-let [[_ score-str] (re-find #"TEST_ALIGNMENT_SCORE:(\d+)" output)]
          (let [score (parse-long score-str)
                justification (some-> (re-find #"TEST_ALIGNMENT_SCORE:\d+\s*\n(.*)" output)
                                      second
                                      str/trim
                                      not-empty)]
            (when (<= 1 score 5)
              (when (and *verbose* justification)
                (println (format "  Test alignment %d/5: %s" score justification)))
              (cond-> {:test-alignment score}
                justification (assoc :test-alignment-justification justification)))))))))

;;; Private tests

(defn has-private-tests?
  "Check whether a challenge has a test-private directory."
  [project-root challenge-name]
  (fs/exists? (fs/path project-root "challenges" challenge-name "test-private")))

(defn challenge-resource-ns-dir
  "Return the conventional underscore resource dir name for a challenge."
  [challenge-name]
  (str/replace challenge-name #"-" "_"))

(defn has-hidden-setup?
  "Check whether a challenge has a hidden setup script in test-resources.
   Accepts either plaintext or encrypted form."
  [project-root challenge-name]
  (let [base (fs/path project-root "challenges" challenge-name "test-resources" (challenge-resource-ns-dir challenge-name) "reference_solution.sh")]
    (or (fs/exists? base)
        (fs/exists? (fs/path (str base ".enc"))))))

(defn has-hidden-teardown?
  "Check whether a challenge has a hidden teardown script in test-resources.
   Accepts either plaintext or encrypted form."
  [project-root challenge-name]
  (let [base (fs/path project-root "challenges" challenge-name "test-resources" (challenge-resource-ns-dir challenge-name) "teardown.sh")]
    (or (fs/exists? base)
        (fs/exists? (fs/path (str base ".enc"))))))

(defn run-hidden-script!
  "Run a hidden challenge script from test-resources. Returns a process result map."
  [project-root challenge-name script-name]
  (let [challenge-dir (str (fs/path project-root "challenges" challenge-name))
        script-path   (str (fs/path "test-resources" (challenge-resource-ns-dir challenge-name) script-name))]
    (invoke-command! ["bash" script-path] challenge-dir)))

(defn run-hidden-setup!
  [project-root challenge-name]
  (run-hidden-script! project-root challenge-name "reference_solution.sh"))

(defn run-hidden-teardown!
  [project-root challenge-name]
  (run-hidden-script! project-root challenge-name "teardown.sh"))

(defn run-private-tests!
  "Run private tests for a challenge. Returns {:exit int, :out str, :err str, :duration-s int}."
  [project-root challenge-name]
  (let [challenge-dir (str (fs/path project-root "challenges" challenge-name))
        cmd ["clojure" "-X:test-private"]
        start (System/currentTimeMillis)
        proc (p/process cmd {:dir challenge-dir :in ""})
        out-fut (future (slurp (:out proc)))
        err-fut (future (slurp (:err proc)))
        done @proc
        duration-s (quot (- (System/currentTimeMillis) start) 1000)]
    {:exit (:exit done)
     :out @out-fut
     :err @err-fut
     :duration-s duration-s}))

(defn- encrypt-other-challenges!
  "Encrypt private files for all challenges except the current one."
  [enc-key project-root current-challenge-name]
  (let [challenge-dirs (fs/list-dir (fs/path project-root "challenges"))]
    (doseq [d challenge-dirs
            :let [name (str (fs/file-name d))]
            :when (and (fs/directory? d)
                       (not= name current-challenge-name)
                       (fs/exists? (fs/path d "README.md")))]
      (encrypt-challenge! enc-key name))))

(defn- decrypt-other-challenges!
  "Decrypt private files for all challenges except the current one."
  [enc-key project-root current-challenge-name]
  (let [challenge-dirs (fs/list-dir (fs/path project-root "challenges"))]
    (doseq [d challenge-dirs
            :let [name (str (fs/file-name d))]
            :when (and (fs/directory? d)
                       (not= name current-challenge-name)
                       (fs/exists? (fs/path d "README.md")))]
      (decrypt-challenge! enc-key name))))

;;; Phase orchestration

(def validation-retry-cap
  "Max number of times a validation phase (2, 4, 6) can FAIL and retry the prior phase."
  3)

(defn impl-root-path
  [project-root challenge-name]
  (str (fs/path project-root "implementations" challenge-name)))

(defn save-attempt!
  "Snapshot the current implementation as attempts/attemptN.clj. Returns invoke-command! result."
  [project-root challenge-name]
  (invoke-command! ["bash" "scripts/save-attempt.sh" challenge-name] project-root))

(defn run-phase!
  "Invoke one phase of a challenge. Clamps the per-call timeout to whatever's
  left in the overall run budget. Returns a result map with everything the
  caller needs to decide next steps and accumulate per-phase telemetry."
  [agent-fns challenge-name phase-id attempt
   project-root agent-name model reasoning run-start-time run-start-millis]
  (let [cmd ((:phase-cmd agent-fns) challenge-name phase-id project-root model reasoning)
        remaining (long (time-remaining-s run-start-millis))
        effective-timeout (min *outer-timeout-s* remaining)
        _ (when *verbose*
            (println (format "  Phase %d (attempt %d) starting (budget remaining: %ds, this-call cap: %ds)..."
                             phase-id attempt remaining effective-timeout)))
        {:keys [exit out err duration-s timed-out?]}
        (binding [*outer-timeout-s* effective-timeout]
          (invoke-command! cmd project-root))
        combined (str out "\n" err)
        verdict (parse-phase-verdict combined)
        transcript-path (save-transcript! project-root agent-name model reasoning
                                          challenge-name out phase-id attempt
                                          run-start-time)
        token-usage (parse-token-usage out)
        cost (compute-cost token-usage (model->pricing model))
        tool-uses (parse-tool-uses out)
        skills-used (parse-skills-used out)
        skill-refs-used (parse-skill-refs-used out)]
    (when *verbose*
      (println (format "  Phase %d (attempt %d) finished: exit=%d duration=%ds verdict=%s"
                       phase-id attempt exit duration-s
                       (if verdict (name verdict) "n/a"))))
    {:phase-id phase-id
     :attempt attempt
     :exit exit
     :timed-out? (boolean timed-out?)
     :duration-s duration-s
     :verdict verdict
     :transcript-path transcript-path
     :token-usage token-usage
     :cost cost
     :tool-uses tool-uses
     :skills-used skills-used
     :skill-refs-used skill-refs-used}))

(defn aggregate-phase-results
  "Sum per-phase telemetry across all phase invocations."
  [phase-results]
  (let [total-tokens   (token-totals (map :token-usage phase-results))
        total-cost     (when (some :cost phase-results) (reduce + 0 (keep :cost phase-results)))
        total-duration (reduce + 0 (map #(or (:duration-s %) 0) phase-results))
        total-tool-uses (reduce + 0 (map #(or (:tool-uses %) 0) phase-results))
        all-skills     (vec (sort (into #{} (mapcat :skills-used phase-results))))
        all-skill-refs (vec (sort (into #{} (mapcat :skill-refs-used phase-results))))]
    {:token-usage     total-tokens
     :cost            total-cost
     :duration-s      total-duration
     :tool-uses       total-tool-uses
     :skills-used     all-skills
     :skill-refs-used all-skill-refs}))

(defn phase-loop!
  "Drive the phase loop. Returns a map:
  {:status :pass | :fail | :timeout
   :iterations int            ;; number of phase-3 invocations (impl attempts)
   :phase-results [...]       ;; one per agent invocation
   :test-output str           ;; final test output (when known)
   :failure-reason str?       ;; populated on :fail/:timeout
   :transcript-path str       ;; path to the most recent agent transcript}

  Phase routing:
  - 0 → 1 → 2
  - 2 pass → 3, 2 fail → 1   (count toward gate 2's retry cap)
  - 3 → 4 (unless skip-4 flag set, then 3 → 5)
  - 4 pass → 5
  - 4 minor-fail → 3 (set skip-4 to true; next time through, skip phase 4)
  - 4 major-fail → 3 (don't set skip-4; phase 4 re-runs)
  - 5 → 6
  - 6 pass → 7
  - 6 minor-fail → 7 (phase 7 absorbs the fix during its iterate loop;
                      no fresh-context re-invocation of phase 5)
  - 6 major-fail → 5 (full re-write needed; phase 6 re-runs after)
  - 7 → done (verdict drives :status)

  validation-retry-cap: max consecutive validation FAILs per gate (2, 4, 6).
  Resets to 0 on PASS at that gate. Both minor-fail and major-fail count.

  An overall wall-clock budget (*overall-timeout-s*) caps the entire run.
  Checked at every loop iteration; per-call subprocess timeouts are clamped
  to the remaining budget."
  [agent-fns challenge-name project-root agent-name model reasoning
   run-start-time run-start-millis]
  (loop [phase-id 0
         attempts {0 1, 1 1, 2 1, 3 1, 4 1, 5 1, 6 1, 7 1}
         skip-flags {4 false}
         validation-fail-counts {2 0, 4 0, 6 0}
         results []]
    (cond
      ;; Overall budget exhausted — abort.
      (<= (time-remaining-s run-start-millis) 0)
      {:status :timeout
       :iterations (max 1 (dec (get attempts 3 1)))
       :phase-results results
       :failure-reason (format "Overall challenge time budget (%ds) exceeded before phase %d."
                               *overall-timeout-s* phase-id)
       :transcript-path (:transcript-path (last results))}

      ;; One-shot skip for phase 4 (after a minor-fail on the prior round).
      (and (= phase-id 4) (get skip-flags 4))
      (recur 5 attempts (assoc skip-flags 4 false) validation-fail-counts results)

      :else
      (let [attempt   (get attempts phase-id 1)
            r         (run-phase! agent-fns challenge-name phase-id attempt
                                  project-root agent-name model reasoning
                                  run-start-time run-start-millis)
            attempts' (assoc attempts phase-id (inc attempt))
            results'  (conj results r)]
        (cond
          (:timed-out? r)
          {:status :timeout
           :iterations (max 1 (dec (get attempts' 3 1)))
           :phase-results results'
           :failure-reason (format "Phase %d (attempt %d) timed out." phase-id attempt)
           :transcript-path (:transcript-path r)}

          (not= 0 (:exit r))
          {:status :fail
           :iterations (max 1 (dec (get attempts' 3 1)))
           :phase-results results'
           :failure-reason (format "Phase %d (attempt %d) exited %d." phase-id attempt (:exit r))
           :transcript-path (:transcript-path r)}

          ;; Phase 2: binary verdict.
          (= phase-id 2)
          (cond
            (= :pass (:verdict r))
            (recur 3 attempts' skip-flags
                   (assoc validation-fail-counts 2 0)
                   results')

            (= :fail (:verdict r))
            (let [prior-fails (get validation-fail-counts 2 0)
                  vfc' (assoc validation-fail-counts 2 (inc prior-fails))]
              (if (< prior-fails validation-retry-cap)
                (do (save-attempt! project-root challenge-name)
                    (recur 1 attempts' skip-flags vfc' results'))
                {:status :fail
                 :iterations (max 1 (dec (get attempts' 3 1)))
                 :phase-results results'
                 :failure-reason (format "Phase 2 failed validation %d times consecutively."
                                         (inc prior-fails))
                 :transcript-path (:transcript-path r)}))

            :else
            {:status :fail
             :iterations (max 1 (dec (get attempts' 3 1)))
             :phase-results results'
             :failure-reason "Phase 2 did not emit PHASE_VALIDATION verdict."
             :transcript-path (:transcript-path r)})

          ;; Phases 4 and 6: three-way verdict (pass/minor-fail/major-fail).
          ;;
          ;; Phase 4 routing:
          ;;   pass       → phase 5
          ;;   minor-fail → phase 3, skip phase 4 on next round (fix is too small to re-validate)
          ;;   major-fail → phase 3, re-run phase 4 (architecture changed)
          ;;
          ;; Phase 6 routing:
          ;;   pass       → phase 7
          ;;   minor-fail → phase 7 (phase 7 absorbs the test fix during its iterate loop;
          ;;                          no fresh-context re-invocation of phase 5 is paid)
          ;;   major-fail → phase 5, re-run phase 6 (test suite needs restructuring)
          (#{4 6} phase-id)
          (cond
            (= :pass (:verdict r))
            (recur (inc phase-id) attempts' skip-flags
                   (assoc validation-fail-counts phase-id 0)
                   results')

            (or (= :minor-fail (:verdict r)) (= :major-fail (:verdict r)))
            (let [prior-fails (get validation-fail-counts phase-id 0)
                  vfc' (assoc validation-fail-counts phase-id (inc prior-fails))
                  next-phase (cond
                               (and (= phase-id 6) (= :minor-fail (:verdict r))) 7
                               (= phase-id 4) 3
                               (= phase-id 6) 5)
                  skip' (if (and (= phase-id 4) (= :minor-fail (:verdict r)))
                          (assoc skip-flags 4 true)
                          skip-flags)]
              (if (< prior-fails validation-retry-cap)
                (do (save-attempt! project-root challenge-name)
                    (recur next-phase attempts' skip' vfc' results'))
                {:status :fail
                 :iterations (max 1 (dec (get attempts' 3 1)))
                 :phase-results results'
                 :failure-reason (format "Phase %d failed validation %d times consecutively."
                                         phase-id (inc prior-fails))
                 :transcript-path (:transcript-path r)}))

            :else
            {:status :fail
             :iterations (max 1 (dec (get attempts' 3 1)))
             :phase-results results'
             :failure-reason (format "Phase %d did not emit a valid PHASE_VALIDATION verdict (got %s)."
                                     phase-id (:verdict r))
             :transcript-path (:transcript-path r)})

          ;; Phase 7 (finish): binary verdict; loop ends with this status.
          ;; The agent is responsible for getting the test suite passing inside
          ;; its own session — no post-phase verification by the runner.
          (= phase-id 7)
          (cond
            (= :pass (:verdict r))
            {:status :pass
             :iterations (max 1 (dec (get attempts' 3 1)))
             :phase-results results'
             :transcript-path (:transcript-path r)}

            (= :fail (:verdict r))
            {:status :fail
             :iterations (max 1 (dec (get attempts' 3 1)))
             :phase-results results'
             :failure-reason "Phase 7 (finish) emitted FAIL — agent could not get tests passing."
             :transcript-path (:transcript-path r)}

            :else
            {:status :fail
             :iterations (max 1 (dec (get attempts' 3 1)))
             :phase-results results'
             :failure-reason (format "Phase 7 did not emit a valid PHASE_VALIDATION verdict (got %s)."
                                     (:verdict r))
             :transcript-path (:transcript-path r)})

          ;; Non-validation phase: advance.
          :else
          (recur (inc phase-id) attempts' skip-flags
                 validation-fail-counts results'))))))

(defn run-challenge
  "Run a single challenge through the agent. Returns a result map:
  {:name str, :status :pass/:fail, :iterations int, :duration-s int,
   :transcript-path str, :error str?, :private-status :pass/:fail/:skip}"
  [challenge agent-name agent-fns project-root model reasoning enc-key]
  (let [challenge-name (:name challenge)]
    (try
      (clean-implementation-dir! project-root challenge-name)

      (let [n-decrypted (decrypt-challenge! enc-key challenge-name)]
        (when (and *verbose* (pos? n-decrypted))
          (println (format "Decrypted %d file(s) for %s" n-decrypted challenge-name))))

      (when (has-hidden-setup? project-root challenge-name)
        (when *verbose*
          (println (format "Preparing hidden setup for %s..." challenge-name)))
        (let [{:keys [exit out err]} (run-hidden-setup! project-root challenge-name)]
          (when (not= 0 exit)
            (throw (ex-info (str "Hidden setup failed for " challenge-name "\n" out err)
                            {:challenge challenge-name :exit exit :out out :err err})))))

      ;; Encrypt private files for this challenge and all other challenges
      (let [n-encrypted (encrypt-challenge! enc-key challenge-name)]
        (when (and *verbose* (pos? n-encrypted))
          (println (format "Encrypted %d file(s) for %s" n-encrypted challenge-name))))
      (encrypt-other-challenges! enc-key project-root challenge-name)

      (if *verbose*
        (println (format "\n=== Running: %s ===" challenge-name))
        (print (format "Running: %-35s" challenge-name)))
      (flush)

      (let [run-start-time (java.time.LocalDateTime/now)
            run-start-millis (System/currentTimeMillis)
            phase-result (phase-loop! agent-fns challenge-name project-root
                                      agent-name model reasoning
                                      run-start-time run-start-millis)
            _ (let [n-decrypted (decrypt-challenge! enc-key challenge-name)]
                (when (and *verbose* (pos? n-decrypted))
                  (println (format "Restored %d file(s) for %s" n-decrypted challenge-name))))
            agg (aggregate-phase-results (:phase-results phase-result))
            status (:status phase-result)
            iterations (:iterations phase-result)
            duration-s (:duration-s agg)
            token-usage (:token-usage agg)
            cost (:cost agg)
            tool-uses (:tool-uses agg)
            skills-used (:skills-used agg)
            skill-refs-used (:skill-refs-used agg)
            transcript-path (:transcript-path phase-result)
            out (or (:test-output phase-result) "")
            err (or (:failure-reason phase-result) "")
            exit (case status :pass 0 :timeout 124 1)]

        ;; Decrypt private files now that agent is done
        (decrypt-challenge! enc-key challenge-name)
        (decrypt-other-challenges! enc-key project-root challenge-name)
        (try
          (let [private-result
                (when (and (has-private-tests? project-root challenge-name)
                           (not= :timeout status))
                  (when *verbose*
                    (println (format "Running private tests for %s..." challenge-name)))
                  (let [result (run-private-tests! project-root challenge-name)]
                    (when (and *verbose* (not= 0 (:exit result)))
                      (binding [*out* *err*]
                        (when (seq (str/trim (:out result)))
                          (println (str/trim (:out result))))
                        (when (seq (str/trim (:err result)))
                          (println (str/trim (:err result))))))
                    result))

                ;; cognitect-test-runner can exit 0 with "Ran 0 tests" when the
                ;; impl namespace is missing or tests fail to load. Treat that
                ;; as :skip, not :pass.
                tests-actually-ran?
                (when private-result
                  (let [combined (str (:out private-result) "\n" (:err private-result))]
                    (if-let [m (re-find #"Ran (\d+) tests" combined)]
                      (pos? (parse-long (second m)))
                      ;; No "Ran N tests" line at all — tests didn't reach that point.
                      false)))

                private-status (cond
                                 (nil? private-result)           nil
                                 (not tests-actually-ran?)       nil
                                 (zero? (:exit private-result))  :pass
                                 :else                           :fail)

                private-output (when (= :fail private-status)
                                 (str (:out private-result) "\n" (:err private-result)))

                ;; Run alignment scoring for passing challenges
                scoring
                (when (= :pass status)
                  (let [impl-scores (do (when *verbose*
                                          (println (format "Scoring alignment for %s..." challenge-name)))
                                        (run-alignment-scoring! project-root challenge-name model))
                        test-scores (do (when *verbose*
                                          (println (format "Scoring test alignment for %s..." challenge-name)))
                                        (run-test-alignment-scoring! project-root challenge-name model))
                        scores (merge impl-scores test-scores)]
                    (when (seq scores)
                      {:scores scores :composite (:alignment scores)})))]


              (let [status-str (case status :pass "PASS" :timeout "TIMEOUT" "FAIL")
                    private-str (case private-status
                                  :pass " | Private: PASS"
                                  :fail " | Private: FAIL"
                                  "")
                    align-str (if-let [a (get-in scoring [:scores :alignment])]
                                (str " | Align: " a "/5")
                                "")
                    test-align-str (if-let [a (get-in scoring [:scores :test-alignment])]
                                     (str " | TestAlign: " a "/5")
                                     "")]
                (println (format "%s%s%s%s (%ds)" status-str private-str align-str test-align-str duration-s)))

              (when (#{:fail :timeout} status)
                (binding [*out* *err*]
                  (println (str "--- " (if (= status :timeout) "TIMEOUT" "FAILED") ": " challenge-name " ---"))
                  (when (seq (str/trim out))
                    (println "stdout:")
                    (println (str/trim out)))
                  (when (seq (str/trim err))
                    (println "stderr:")
                    (println (str/trim err)))
                  (println (str "exit code: " exit))
                  (println "---")))

              (let [priv (or private-status :skip)
                    score (compute-challenge-score status priv iterations)]
                (merge {:name challenge-name
                        :status status
                        :private-status priv
                        :challenge-score score
                        :iterations iterations
                        :duration-s duration-s
                        :cost cost
                        :tool-uses tool-uses
                        :skills-used skills-used
                        :skill-refs-used skill-refs-used
                        :scoring scoring
                        :transcript-path transcript-path
                        :error (when (#{:fail :timeout} status)
                                 (let [err-str (str/trim err)]
                                   (when (seq err-str)
                                     err-str)))}
                       token-usage)))
          (finally
            (when (has-hidden-teardown? project-root challenge-name)
              (when *verbose*
                (println (format "Running hidden teardown for %s..." challenge-name)))
              (let [{:keys [exit out err]} (run-hidden-teardown! project-root challenge-name)]
                (when (not= 0 exit)
                  (binding [*out* *err*]
                    (println (format "WARN: hidden teardown failed for %s" challenge-name))
                    (when (seq (str/trim out))
                      (println (str/trim out)))
                    (when (seq (str/trim err))
                      (println (str/trim err))))))))))

      (catch Exception e
        (try
          (decrypt-challenge! enc-key challenge-name)
          (catch Exception _))
        (try (decrypt-other-challenges! enc-key project-root challenge-name) (catch Exception _))
        (try
          (when (has-hidden-teardown? project-root challenge-name)
            (run-hidden-teardown! project-root challenge-name))
          (catch Exception _))
        (println (format "FAIL (error: %s)" (.getMessage e)))
        {:name challenge-name
         :status :fail
         :private-status :skip
         :challenge-score 0
         :iterations 0
         :duration-s 0
         :input-tokens 0
         :output-tokens 0
         :cache-creation-tokens 0
         :cache-read-tokens 0
         :cost nil
         :tool-uses 0
         :skills-used []
         :skill-refs-used []
         :scoring nil
         :error (.getMessage e)}))))

(defn run-challenges
  "Run all challenges sequentially, returning a vector of result maps."
  [challenges agent-key agent-name project-root model reasoning enc-key]
  (let [agent-fns (get agents agent-key)]
    (when-not agent-fns
      (binding [*out* *err*]
        (println (str "Error: unknown agent '" (name agent-key) "'. Use 'claude' or 'codex'.")))
      (System/exit 1))
    (println)
    (mapv #(run-challenge % agent-name agent-fns project-root model reasoning enc-key) challenges)))

;;; Formatting

(defn format-duration
  "Format elapsed seconds as a human-readable duration string.
  Returns `Xm Ys` when >= 60s, or `Xs` when < 60s."
  [seconds]
  (let [s (Math/round (double seconds))]
    (if (< s 60)
      (str s "s")
      (let [m (quot s 60)
            r (rem s 60)]
        (str m "m " r "s")))))

;;; Reporting

(defn format-filters
  "Format active CLI filters into a human-readable string."
  [opts]
  (let [parts (cond-> []
                (:filter opts)     (conj (str "filter=" (:filter opts)))
                (:batch opts)      (conj (str "batch=" (:batch opts)))
                (:difficulty opts) (conj (str "difficulty=" (:difficulty opts))))]
    (when (seq parts)
      (str/join ", " parts))))

(defn resolve-effort
  "Resolve the effective effort level: explicit flag, settings.json, or 'default'."
  [reasoning]
  (or reasoning
      (try
        (let [settings-path (fs/path (fs/home) ".claude" "settings.json")]
          (when (fs/exists? settings-path)
            (get (json/parse-string (slurp (str settings-path))) "effortLevel")))
        (catch Exception _ nil))
      "default"))

(defn print-run-header
  "Print a header at the start of a challenge run."
  [agent-name challenge-count opts model reasoning]
  (println)
  (println (format "Agent: %s%s [effort: %s] | Challenges: %d"
                   agent-name
                   (if model (str " (" model ")") "")
                   (resolve-effort reasoning)
                   challenge-count))
  (when-let [filters (format-filters opts)]
    (println (format "Filters: %s" filters)))
  (println (str/join (repeat 60 "-"))))

(defn format-score
  "Format a score integer as a string, or \"-\" when nil."
  [v]
  (if v (str v) "-"))

(defn format-composite
  "Format a composite score as a string with one decimal, or \"-\" when nil."
  [v]
  (if v (format "%.1f" (double v)) "-"))

(defn result-table-rows
  "Format results into table row strings.
  Returns nil for empty results."
  [results]
  (when (seq results)
    (let [max-name   (max 9 (apply max (map #(count (:name %)) results)))
          max-skills (max 6 (apply max (map #(count (str/join ", " (:skills-used % []))) results)))
          max-refs   (max 10 (apply max (map #(count (str/join ", " (:skill-refs-used % []))) results)))]
      {:header (format (str "| %-" max-name "s | %-7s | %-7s | %-5s | %-10s | %-8s | %-9s | %-10s | %-12s | %-10s | %-9s | %-10s | %-" max-skills "s | %-" max-refs "s | %-5s | %-9s |")
                       "Challenge" "Status" "Private" "Score" "Iterations" "Duration"
                       "In Tokens" "Out Tokens" "Cache Create" "Cache Read" "Tool Uses" "Cost" "Skills" "Skill Refs"
                       "Align" "TestAlign")
       :separator (str "|" (str/join (repeat (+ max-name 2) "-"))
                       "|---------|---------|-------|------------|----------|-----------|------------|--------------|------------|-----------|------------|"
                       (str/join (repeat (+ max-skills 2) "-"))
                       "|"
                       (str/join (repeat (+ max-refs 2) "-"))
                       "|-------|-----------|")
       :rows (mapv (fn [{:keys [name status private-status challenge-score iterations duration-s
                                input-tokens output-tokens
                                cache-creation-tokens cache-read-tokens tool-uses cost
                                skills-used skill-refs-used scoring]}]
                     (let [scores (:scores scoring)]
                       (format (str "| %-" max-name "s | %-7s | %-7s | %-5d | %-10d | %-7ds | %-9d | %-10d | %-12d | %-10d | %-9d | %-10s | %-" max-skills "s | %-" max-refs "s | %-5s | %-9s |")
                               name
                               (case status :pass "PASS" :timeout "TIMEOUT" "FAIL")
                               (case private-status :pass "PASS" :fail "FAIL" :skip "-" "-")
                               (or challenge-score 0)
                               iterations
                               duration-s
                               input-tokens
                               output-tokens
                               cache-creation-tokens
                               cache-read-tokens
                               (or tool-uses 0)
                               (format-cost cost)
                               (str/join ", " (or skills-used []))
                               (str/join ", " (or skill-refs-used []))
                               (format-score (:alignment scores))
                               (format-score (:test-alignment scores)))))
                   results)})))

(defn print-summary-table
  "Print a formatted summary table to the console.
  When total-elapsed-s is provided, displays total elapsed time after the
  summary line."
  ([results] (print-summary-table results nil))
  ([results total-elapsed-s]
   (when-let [{:keys [header separator rows]} (result-table-rows results)]
     (let [passed   (count (filterv #(= :pass (:status %)) results))
           timed-out (count (filterv #(= :timeout (:status %)) results))
           failed   (count (filterv #(= :fail (:status %)) results))
           priv-passed (count (filterv #(= :pass (:private-status %)) results))
           priv-failed (count (filterv #(= :fail (:private-status %)) results))
           priv-total  (+ priv-passed priv-failed)
           {:keys [input-tokens output-tokens
                   cache-creation-tokens cache-read-tokens]} (token-totals results)]
       (println)
       (println header)
       (println separator)
       (doseq [row rows]
         (println row))
       (println)
       (let [total-cost      (when (some :cost results) (reduce + 0 (keep :cost results)))
             total-tool-uses (reduce + 0 (map #(or (:tool-uses %) 0) results))
             avg-score       (/ (reduce + 0.0 (map #(or (:challenge-score %) 0) results))
                                (count results))
             align-vals      (keep #(get-in % [:scoring :scores :alignment]) results)
             avg-align       (when (seq align-vals)
                               (/ (reduce + 0.0 align-vals) (count align-vals)))
             test-align-vals (keep #(get-in % [:scoring :scores :test-alignment]) results)
             avg-test-align  (when (seq test-align-vals)
                               (/ (reduce + 0.0 test-align-vals) (count test-align-vals)))]
         (println (format "Challenges: %d | Passed: %d | Failed: %d | Timed out: %d"
                          (count results) passed failed timed-out))
         (when (pos? priv-total)
           (println (format "Private tests: %d/%d passed" priv-passed priv-total)))
         (println (format "Average score: %.1f" avg-score))
         (when avg-align
           (println (format "Average alignment: %.1f/5 (n=%d)" avg-align (count align-vals))))
         (when avg-test-align
           (println (format "Average test alignment: %.1f/5 (n=%d)" avg-test-align (count test-align-vals))))
         (println (format "Tokens: In: %d | Out: %d | Cache Create: %d | Cache Read: %d | Tool Uses: %d | Cost: %s"
                          input-tokens output-tokens cache-creation-tokens cache-read-tokens
                          total-tool-uses (format-cost total-cost))))
       (when total-elapsed-s
         (println (str "Total elapsed: " (format-duration total-elapsed-s))))))))

(defn generate-report
  "Generate a markdown report file. Returns the report file path.
  opts is an optional map with keys:
    :total-elapsed-s - includes total elapsed time in the report footer
    :model           - includes model in filename and content
    :reasoning       - includes reasoning in content; also in filename when model is present"
  ([results agent-name project-root]
   (generate-report results agent-name project-root {}))
  ([results agent-name project-root opts]
   (let [{:keys [total-elapsed-s model reasoning]} opts
         now (java.time.LocalDateTime/now)
         date-str (.format now (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))
         time-str (.format now (java.time.format.DateTimeFormatter/ofPattern "HHmmss"))
         timestamp (.format now (java.time.format.DateTimeFormatter/ofPattern
                                 "yyyy-MM-dd HH:mm:ss"))
         reports-dir (fs/path project-root ".." "reports")
         filename (cond
                    (and model reasoning) (format "%s-%s-%s-%s-%s.md" date-str time-str agent-name model reasoning)
                    model                 (format "%s-%s-%s-%s.md" date-str time-str agent-name model)
                    :else                 (format "%s-%s-%s.md" date-str time-str agent-name))
         report-path (fs/path reports-dir filename)
         passed    (count (filterv #(= :pass (:status %)) results))
         timed-out (count (filterv #(= :timeout (:status %)) results))
         failed    (count (filterv #(= :fail (:status %)) results))
         sb (StringBuilder.)]
     (fs/create-dirs reports-dir)
     (.append sb (format "# Challenge Run Report - %s\n" timestamp))
     (.append sb (format "Agent: %s\n" agent-name))
     (when model
       (.append sb (format "Model: %s\n" model)))
     (when reasoning
       (.append sb (format "Reasoning: %s\n" reasoning)))
     (.append sb (format "Challenges: %d | Passed: %d | Failed: %d | Timed out: %d\n\n"
                         (count results) passed failed timed-out))
     (.append sb "| Challenge | Status | Private | Score | Iterations | Duration | In Tokens | Out Tokens | Cache Create | Cache Read | Tool Uses | Cost | Skills | Skill Refs | Align | TestAlign |\n")
     (.append sb "|-----------|--------|---------|-------|------------|----------|-----------|------------|--------------|------------|-----------|------|--------|------------|-------|----------|\n")
     (doseq [{:keys [name status private-status challenge-score iterations duration-s
                     input-tokens output-tokens
                     cache-creation-tokens cache-read-tokens tool-uses cost
                     skills-used skill-refs-used scoring]} results]
       (let [scores (:scores scoring)]
         (.append sb (format "| %s | %s | %s | %d | %d | %ds | %d | %d | %d | %d | %d | %s | %s | %s | %s | %s |\n"
                             name
                             (case status :pass "PASS" :timeout "TIMEOUT" "FAIL")
                             (case private-status :pass "PASS" :fail "FAIL" :skip "-" "-")
                             (or challenge-score 0)
                             iterations
                             duration-s
                             input-tokens
                             output-tokens
                             cache-creation-tokens
                             cache-read-tokens
                             (or tool-uses 0)
                             (format-cost cost)
                             (str/join ", " (or skills-used []))
                             (str/join ", " (or skill-refs-used []))
                             (format-score (:alignment scores))
                             (format-score (:test-alignment scores))))))
     (let [{:keys [input-tokens output-tokens
                   cache-creation-tokens cache-read-tokens]} (token-totals results)
           total-cost      (when (some :cost results) (reduce + 0 (keep :cost results)))
           total-tool-uses (reduce + 0 (map #(or (:tool-uses %) 0) results))
           avg-score       (/ (reduce + 0.0 (map #(or (:challenge-score %) 0) results))
                              (count results))
           align-vals      (keep #(get-in % [:scoring :scores :alignment]) results)
           avg-align       (when (seq align-vals)
                             (/ (reduce + 0.0 align-vals) (count align-vals)))
           test-align-vals (keep #(get-in % [:scoring :scores :test-alignment]) results)
           avg-test-align  (when (seq test-align-vals)
                             (/ (reduce + 0.0 test-align-vals) (count test-align-vals)))]
       (.append sb (format "\n**Average score:** %.1f\n" avg-score))
       (when avg-align
         (.append sb (format "**Average alignment:** %.1f/5 (n=%d)\n" avg-align (count align-vals))))
       (when avg-test-align
         (.append sb (format "**Average test alignment:** %.1f/5 (n=%d)\n" avg-test-align (count test-align-vals))))
       (.append sb (format "**Tokens:** In: %d | Out: %d | Cache Create: %d | Cache Read: %d | Tool Uses: %d | Cost: %s\n"
                           input-tokens output-tokens cache-creation-tokens cache-read-tokens
                           total-tool-uses (format-cost total-cost))))
     (let [justified (filterv #(some (fn [k] (get-in % [:scoring :scores k]))
                                     [:alignment-justification :test-alignment-justification])
                              results)]
       (when (seq justified)
         (.append sb "\n## Alignment Justifications\n\n")
         (doseq [{:keys [name scoring]} justified]
           (let [{:keys [alignment alignment-justification
                         test-alignment test-alignment-justification]} (:scores scoring)]
             (when alignment-justification
               (.append sb (format "- **%s** impl (%d/5): %s\n" name alignment alignment-justification)))
             (when test-alignment-justification
               (.append sb (format "- **%s** tests (%d/5): %s\n" name test-alignment test-alignment-justification)))))))
     (when total-elapsed-s
       (.append sb (format "\n**Total elapsed:** %s\n" (format-duration total-elapsed-s))))
     (spit (str report-path) (str sb))
     (str report-path))))

;;; Main

(defn -main [args]
  (let [opts (cli/parse-opts args {:spec cli-spec})
        project-root (str (fs/parent (fs/absolutize "bb.edn")))]
    (when (:help opts)
      (print-usage)
      (System/exit 0))

    (let [challenge-order-path (fs/path project-root "CHALLENGE_ORDER.md")]
      (when-not (fs/exists? challenge-order-path)
        (binding [*out* *err*]
          (println "Error: CHALLENGE_ORDER.md not found at" (str challenge-order-path)))
        (System/exit 1))

      (let [ordered-challenges (parse-challenge-order (str challenge-order-path))
            all-challenges (discover-all-challenges project-root ordered-challenges)
            filtered (filter-challenges all-challenges opts)
            {:keys [local cluster]} (partition-by-cluster filtered)
            cluster-with-setup (filterv #(has-hidden-setup? project-root (:name %)) cluster)
            cluster-without-setup (filterv #(not (has-hidden-setup? project-root (:name %))) cluster)
            ;; Gate only cluster challenges that do not provide their own hidden setup
            cluster-available (and (seq cluster-without-setup) (cluster-running?))
            _ (when (seq cluster-without-setup)
                (if cluster-available
                  (println "Cluster available: including Batch 5 challenges.")
                  (do
                    (println "Cluster unavailable: skipping Batch 5 challenges without hidden setup.")
                    (println "  Start the local cluster and retry, or check RAMA_CONDUCTOR_HOST/RAMA_CONDUCTOR_PORT."))))
            _ (when (seq cluster-with-setup)
                (println "Including Batch 5 challenges with hidden setup."))
            valid-challenges (concat local cluster-with-setup (if cluster-available cluster-without-setup []))
            {:keys [valid missing]} (validate-challenges (vec valid-challenges) project-root)
            agent-key (keyword (:agent opts))
            agent-name (:agent opts)
            model (:model opts)
            reasoning (:reasoning opts)]

        (when (seq missing)
          (binding [*out* *err*]
            (println "Warning: missing challenge directories:")
            (doseq [{:keys [name]} missing]
              (println (str "  - " name)))))

        (when (empty? valid)
          (println "No challenges found matching filters.")
          (System/exit 0))

        (print-run-header agent-name (count valid) opts model reasoning)

        (let [enc-key       (challenge-encryption-key)
              start-ms      (System/currentTimeMillis)
              results       (binding [*verbose* (or (:verbose opts) (:pretty opts))
                                      *pretty* (boolean (:pretty opts))]
                              (run-challenges valid agent-key agent-name project-root model reasoning enc-key))
              total-elapsed-s (/ (- (System/currentTimeMillis) start-ms) 1000.0)]
          (print-summary-table results total-elapsed-s)
          (let [report-path (generate-report results agent-name project-root
                                             {:total-elapsed-s total-elapsed-s
                                              :model model
                                              :reasoning reasoning})]
            (println)
            (println (str "Report saved: " report-path)))

          ;; Append to results database
          (let [db-path (str (fs/path project-root ".." "reports" "results.edn"))
                timestamp (str (java.time.Instant/now))
                records (mapv (fn [{:keys [name status private-status challenge-score iterations duration-s
                                           input-tokens output-tokens
                                           cache-creation-tokens cache-read-tokens
                                           tool-uses cost scoring]}]
                                {:timestamp            timestamp
                                 :agent                agent-name
                                 :model                model
                                 :reasoning            reasoning
                                 :challenge             name
                                 :status               status
                                 :private-status       private-status
                                 :challenge-score      (or challenge-score 0)
                                 :iterations           iterations
                                 :duration-s           duration-s
                                 :input-tokens         (or input-tokens 0)
                                 :output-tokens        (or output-tokens 0)
                                 :cache-creation-tokens (or cache-creation-tokens 0)
                                 :cache-read-tokens    (or cache-read-tokens 0)
                                 :tool-uses            (or tool-uses 0)
                                 :cost                 cost
                                 :scoring              scoring})
                              results)]
            (fs/create-dirs (fs/parent db-path))
            (spit db-path
                  (str (str/join "\n" (map pr-str records)) "\n")
                  :append true))

          results)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
