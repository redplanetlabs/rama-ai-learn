#!/usr/bin/env bb

;; Watch a running dynamic-workflow run and stream its per-phase subagent
;; transcripts live. The workflow runtime runs each phase as an isolated
;; subagent whose work never reaches the top-level `claude --output-format
;; stream-json` output; it only lands on disk under:
;;
;;   ~/.claude/projects/<proj>/<session>/subagents/workflows/wf_<id>/
;;       agent-<id>.jsonl     per-phase transcript (standard Claude JSONL)
;;       journal.jsonl        started/result events per agentId
;;
;; This script auto-discovers the most-recently-written wf_* dir (the live
;; run), follows every agent-*.jsonl as it grows, and prints each new record
;; through the same formatter as `bb format-transcript`.

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str])

;; Reuse the transcript formatter (format-record!, parse-record, color helpers).
(load-file "scripts/format_transcript.bb")

(def watch-cli-spec
  {:dir {:desc "Pin a specific wf_* dir to watch (default: newest live run)"}
   :latest {:desc "Re-detect the newest run on every poll instead of locking at startup"
            :coerce :boolean}
   :from-now {:desc "Start at the end of existing files (skip backlog already written)"
              :coerce :boolean}
   :poll {:desc "Poll interval in milliseconds" :coerce :long :default 400}
   :stale {:desc "Warn if newest run wrote nothing in this many seconds" :coerce :long :default 120}
   :no-thinking {:desc "Hide thinking blocks" :alias :T :coerce :boolean}
   :no-tool-results {:desc "Hide tool result content" :alias :R :coerce :boolean}
   :no-system {:desc "Hide system events" :alias :S :coerce :boolean}
   :compact {:desc "Compact tool results (first/last 5 lines)" :alias :c :coerce :boolean}
   :help {:desc "Show usage" :alias :h :coerce :boolean}})

(defn print-usage []
  (println "Usage: bb watch-workflow [options]")
  (println)
  (println "Stream a running dynamic-workflow's per-phase subagent transcripts live.")
  (println "Auto-discovers the newest live run under ~/.claude/projects. Run it in a")
  (println "second shell while a `/rama-challenge` workflow runs.")
  (println)
  (println "Options:")
  (println "      --dir PATH          Pin a specific wf_* dir (default: newest live run)")
  (println "      --latest            Re-detect the newest run every poll")
  (println "      --from-now          Skip the backlog; only show records written after start")
  (println "      --poll MS           Poll interval in ms (default 400)")
  (println "      --stale SECS        Stale-run warning threshold (default 120)")
  (println "  -c, --compact           Compact tool results (first/last 5 lines)")
  (println "  -T, --no-thinking       Hide thinking blocks")
  (println "  -R, --no-tool-results   Hide tool result content")
  (println "  -S, --no-system         Hide system events")
  (println "  -h, --help              Show this help"))

;;; Discovery

(defn- projects-root []
  (fs/path (System/getProperty "user.home") ".claude" "projects"))

(defn- wf-dirs []
  (->> (fs/glob (projects-root) "*/*/subagents/workflows/wf_*")
       (filter fs/directory?)))

(defn- dir-latest-mtime [d]
  (->> (fs/list-dir d)
       (filter fs/regular-file?)
       (map #(.toMillis (fs/last-modified-time %)))
       (reduce max 0)))

(defn- newest-wf-dir []
  (let [ds (wf-dirs)]
    (when (seq ds)
      (apply max-key dir-latest-mtime ds))))

;;; Tail state: per-file byte offset + partial-line buffer

(def state (atom {:offsets {} :partials {} :seen #{} :last-agent nil}))

(defn- read-appended
  "Read bytes appended to path since offset. Returns [new-offset text-or-nil]."
  [path offset]
  (let [f (fs/file path)
        len (.length f)]
    (if (<= len offset)
      [offset nil]
      (with-open [raf (java.io.RandomAccessFile. f "r")]
        (.seek raf offset)
        (let [buf (byte-array (- len offset))]
          (.readFully raf buf)
          [len (String. buf "UTF-8")])))))

(defn- drain-lines!
  "Return the complete lines newly appended to path, buffering any trailing
  partial line until its newline arrives."
  [path]
  (let [off (get-in @state [:offsets path] 0)
        [new-off chunk] (read-appended path off)]
    (if-not chunk
      []
      (let [combined (str (get-in @state [:partials path] "") chunk)
            parts (str/split combined #"\n" -1)
            complete (butlast parts)
            remainder (last parts)]
        (swap! state #(-> %
                          (assoc-in [:offsets path] new-off)
                          (assoc-in [:partials path] remainder)))
        (vec complete)))))

;;; Rendering

(defn- agent8 [path]
  (if-let [id (second (re-find #"agent-([0-9a-f]+)" (str (fs/file-name path))))]
    (subs id 0 (min 8 (count id)))
    (str (fs/file-name path))))

;; Stable per-agent color so interleaved records from different subagents are
;; visually separable. Deterministic for a given agent id within a run.
(def ^:private tag-palette [:cyan :magenta :blue :yellow :green :red :white])

(defn- agent-color [path]
  (nth tag-palette (mod (Math/abs (hash (agent8 path))) (count tag-palette))))

(defn- user-string-prompt?
  "The first record of a phase transcript: a user message whose content is the
  raw phase prompt string (not an array of content blocks)."
  [rec]
  (and (= "user" (:type rec)) (string? (get-in rec [:message :content]))))

(defn- clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- print-banner! [path rec]
  (let [text (get-in rec [:message :content])
        lines (str/split-lines (or text ""))
        phase (some->> lines
                       (some #(when (str/includes? % "THIS PHASE:") %))
                       (#(str/replace % #"^.*THIS PHASE:\s*" "")))
        lens (some->> lines
                      (some #(when (str/includes? % "YOUR LENS:") %))
                      (#(str/replace % #"^.*YOUR LENS:\s*" "")))
        label (str (clip (or (not-empty phase) "phase") 70)
                   (when (not-empty lens) (str " · " (clip lens 40))))
        col (agent-color path)]
    (println)
    (println (c col (bold (str "━━━━━ ▶ " label "  [" (agent8 path) "] ━━━━━"))))
    (swap! state #(-> %
                      (assoc :last-agent (str path))
                      (assoc-in [:labels (str path)] label)))))

(defn- render-agent-line! [path line opts]
  (when-not (str/blank? line)
    (when-let [rec (parse-record line)]
      (if (user-string-prompt? rec)
        (print-banner! path rec)
        (do
          (when (not= (:last-agent @state) (str path))
            (swap! state assoc :last-agent (str path))
            (let [label (get-in @state [:labels (str path)])]
              (println (c (agent-color path)
                          (str "· [" (agent8 path) "]" (when label (str " " label)))))))
          (format-record! :claude rec opts)
          (flush))))))

(defn- render-journal-line! [line]
  (when-let [rec (parse-record line)]
    (when (= "result" (:type rec))
      (let [summary (first (str/split-lines (or (:result rec) "")))]
        (println (c :green (str "  ✓ " (clip (or (:agentId rec) "") 8) "  " (clip summary 140))))
        (flush)))))

;;; Follow loop

(defn- ensure-seen! [path opts]
  (when-not (contains? (:seen @state) (str path))
    (swap! state update :seen conj (str path))
    (when (:from-now opts)
      (swap! state assoc-in [:offsets (str path)] (.length (fs/file path))))))

(defn- tick! [wf opts]
  (doseq [path (sort-by str (fs/glob wf "agent-*.jsonl"))]
    (ensure-seen! path opts)
    (doseq [line (drain-lines! (str path))]
      (render-agent-line! path line opts)))
  (let [jpath (fs/path wf "journal.jsonl")]
    (when (fs/exists? jpath)
      (ensure-seen! jpath opts)
      (doseq [line (drain-lines! (str jpath))]
        (render-journal-line! line)))))

(defn run [opts]
  (let [pinned (some-> (:dir opts) fs/path)
        initial (or pinned (newest-wf-dir))]
    (when-not initial
      (println (str "No workflow run dirs found under " (projects-root) "."))
      (println "Start a /rama-challenge workflow first, then run this watcher.")
      (System/exit 1))
    (let [age-s (quot (- (System/currentTimeMillis) (dir-latest-mtime initial)) 1000)]
      (when (> age-s (:stale opts))
        (println (c :yellow (str "WARN: newest run last wrote " age-s "s ago — it may be stale. "
                                 "Use --dir to pin the right one.")))))
    (println (c :gray (str "watching " initial "  (poll " (:poll opts) "ms · Ctrl-C to stop)")))
    (loop []
      (let [wf (cond pinned pinned
                     (:latest opts) (or (newest-wf-dir) initial)
                     :else initial)]
        (try
          (tick! wf opts)
          (catch Exception e
            (binding [*out* *err*]
              (println (str "watch tick error: " (.getMessage e)))))))
      (Thread/sleep (:poll opts))
      (recur))))

(defn -main [args]
  (let [{:keys [opts]} (cli/parse-args args {:spec watch-cli-spec})]
    (if (:help opts)
      (print-usage)
      (run opts))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
