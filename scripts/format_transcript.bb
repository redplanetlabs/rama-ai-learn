#!/usr/bin/env bb

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def cli-spec
  {:no-thinking {:desc "Hide thinking blocks"
                 :alias :T
                 :coerce :boolean}
   :no-tool-results {:desc "Hide tool result content"
                     :alias :R
                     :coerce :boolean}
   :no-hooks {:desc "Hide hook events"
              :alias :H
              :coerce :boolean}
   :no-system {:desc "Hide system events (init, hooks)"
               :alias :S
               :coerce :boolean}
   :compact {:desc "Compact tool results (first/last 5 lines)"
             :alias :c
             :coerce :boolean}
   :stdin {:desc "Read JSONL from stdin and format live (also implied by '-' or no path)"
           :coerce :boolean}
   :help {:desc "Show usage"
          :alias :h
          :coerce :boolean}})

(defn print-usage []
  (println "Usage: bb format-transcript [options] [<transcript.jsonl> | -]")
  (println)
  (println "Format a Claude Code or Codex JSONL transcript for readable display.")
  (println "Auto-detects transcript format.")
  (println)
  (println "With '-', --stdin, or no path, reads JSONL from stdin and formats")
  (println "each record live. Pipe a stream-json run into it, e.g.:")
  (println "  claude --output-format stream-json --verbose -p '...' | bb format-transcript -")
  (println)
  (println "Options:")
  (println "  -T, --no-thinking       Hide thinking blocks")
  (println "  -R, --no-tool-results   Hide tool result content")
  (println "  -H, --no-hooks          Hide hook events")
  (println "  -S, --no-system         Hide all system events")
  (println "  -c, --compact           Compact tool results (first/last 5 lines)")
  (println "      --stdin             Read JSONL from stdin and format live")
  (println "  -h, --help              Show this help"))

;;; ANSI colors

(def ^:private colors
  {:reset   "\033[0m"
   :bold    "\033[1m"
   :dim     "\033[2m"
   :blue    "\033[34m"
   :green   "\033[32m"
   :yellow  "\033[33m"
   :red     "\033[31m"
   :cyan    "\033[36m"
   :magenta "\033[35m"
   :white   "\033[37m"
   :gray    "\033[90m"})

(defn- c [color-key & strs]
  (str (colors color-key) (apply str strs) (:reset colors)))

(defn- bold [s] (str (:bold colors) s (:reset colors)))

;;; Formatting helpers

(defn- hrule []
  (c :gray (apply str (repeat 72 "─"))))

(defn- indent [s n]
  (let [prefix (apply str (repeat n " "))]
    (->> (str/split-lines s)
         (mapv #(str prefix %))
         (str/join "\n"))))

(defn- compact-text
  "Show first and last n lines with an elision marker."
  [text n]
  (let [lines (str/split-lines text)]
    (if (<= (count lines) (* 2 n))
      text
      (str (str/join "\n" (take n lines))
           "\n"
           (c :gray (str "  ... (" (- (count lines) (* 2 n)) " lines omitted) ..."))
           "\n"
           (str/join "\n" (take-last n lines))))))

(defn- format-tool-input
  "Format tool_use input for display."
  [name input]
  (case name
    "Bash"     (str (c :gray "$ ") (:command input))
    "Read"     (let [path (:file_path input)]
                 (str (c :gray "cat ") path
                      (when-let [o (:offset input)] (str " +" o))
                      (when-let [l (:limit input)] (str " [" l " lines]"))))
    "Write"    (str (c :gray "write ") (:file_path input)
                    "\n" (indent (compact-text (or (:content input) "") 10) 4))
    "Edit"     (str (c :gray "edit ") (:file_path input)
                    "\n" (c :red (indent (or (:old_string input) "") 4))
                    "\n" (c :green (indent (or (:new_string input) "") 4)))
    "Glob"     (str (c :gray "glob ") (:pattern input)
                    (when-let [p (:path input)] (str " in " p)))
    "Grep"     (str (c :gray "grep ") (pr-str (:pattern input))
                    (when-let [p (:path input)] (str " in " p))
                    (when-let [g (:glob input)] (str " --glob " g)))
    "Skill"    (str (c :gray "skill ") (:skill input)
                    (when-let [a (:args input)] (str " " a)))
    "Agent"    (str (c :gray "agent ") (:description input)
                    (when-let [st (:subagent_type input)] (str " [" st "]")))
    ;; MCP tools
    (if (str/starts-with? (or name "") "mcp__")
      (str (c :gray (str name " "))
           (pr-str (select-keys input (take 3 (keys input)))))
      ;; Default
      (str (c :gray (str name " "))
           (let [s (pr-str input)]
             (if (> (count s) 200) (str (subs s 0 200) "...") s))))))

(defn- strip-system-reminders
  "Remove <system-reminder>...</system-reminder> blocks from text."
  [text]
  (str/replace text #"(?s)\n?\s*<system-reminder>.*?</system-reminder>\s*" ""))

(defn- format-tool-result
  "Format a tool result content string."
  [content opts]
  (let [text (cond
               (string? content) content
               (sequential? content) (str/join "\n" (map #(or (:text %) (str %)) content))
               :else (str content))
        text (strip-system-reminders text)]
    (cond
      (str/blank? text) nil
      (:no-tool-results opts) nil
      (:compact opts) (compact-text text 5)
      :else text)))

;;; Event formatters

(defn- format-system-init [record]
  (let [data (or record {})]
    (str (c :cyan (bold "╭── Session "))
         (c :cyan (str "model=" (:model data (get-in data [:message :model] "?"))
                       " cwd=" (:cwd data "?")))
         "\n" (c :cyan "│"))))

(defn- format-system-hook [record]
  (let [name (:hook_name record)
        event (:hook_event record)]
    (c :gray (str "  hook: " name " [" event "]"))))

(defn- format-hook-response [record]
  (let [name (:hook_name record)
        code (:exit_code record)
        outcome (:outcome record)]
    (c :gray (str "  hook: " name " → " outcome
                  (when (and code (not= code 0)) (str " (exit " code ")"))))))

(defn- format-thinking [text]
  (let [lines (str/split-lines (or text ""))
        display (if (> (count lines) 6)
                  (str (str/join "\n" (take 3 lines))
                       "\n" (c :gray (str "... (" (- (count lines) 6) " lines) ..."))
                       "\n" (str/join "\n" (take-last 3 lines)))
                  (str/join "\n" lines))]
    (str (c :magenta "  💭 ") (indent (c :magenta display) 5))))

(defn- format-assistant-text [text]
  (str "\n" (bold (c :green "Claude")) "\n" text "\n"))

(defn- format-tool-use [content opts]
  (let [name (:name content)
        input (or (:input content) {})]
    (str (c :yellow (str "  ⚡ " name " "))
         (format-tool-input name input))))

(defn- format-user-text [text]
  (str "\n" (hrule)
       "\n" (bold (c :blue "You")) "\n" text "\n"))

(defn- process-assistant-record [record opts]
  (let [msg (:message record)
        content (or (:content msg) [])]
    (->> content
         (keep
          (fn [block]
            (case (:type block)
              "thinking" (when-not (:no-thinking opts)
                           (format-thinking (:thinking block)))
              "text"     (format-assistant-text (:text block))
              "tool_use" (format-tool-use block opts)
              nil)))
         (str/join "\n"))))

(defn- process-user-record [record opts]
  (let [msg (:message record)
        content (or (:content msg) [])
        parts (keep
               (fn [block]
                 (case (:type block)
                   "text" (let [t (:text block)]
                            (when-not (str/blank? t)
                              (format-user-text t)))
                   "tool_result"
                   (let [result-content (:content block)
                         tool-id (:tool_use_id block)
                         is-error (:is_error block)
                         formatted (format-tool-result result-content opts)]
                     (when formatted
                       (str (if is-error
                              (c :red "  ✗ ")
                              (c :gray "  ↩ "))
                            (c :gray (str "[" (when tool-id (subs tool-id 0 (min 12 (count tool-id)))) "] "))
                            (when-not (str/blank? formatted)
                              (str "\n" (indent formatted 6))))))
                   nil))
               content)]
    (str/join "\n" parts)))

;;; Codex transcript formatting

(defn- format-codex-session [record]
  (let [thread-id (:thread_id record)]
    (str (c :cyan (bold "╭── Codex Session "))
         (when thread-id (c :cyan (str "thread=" thread-id)))
         "\n" (c :cyan "│"))))

(defn- format-codex-agent-message [item]
  (let [text (:text item)]
    (when-not (str/blank? text)
      (str "\n" (bold (c :green "Codex")) "\n" text "\n"))))

(defn- format-codex-command-started [item]
  (let [cmd (:command item)]
    (str (c :yellow "  ⚡ shell ")
         (c :gray "$ ") cmd)))

(defn- format-codex-command-completed [item opts]
  (let [cmd (:command item)
        output (:aggregated_output item)
        exit-code (:exit_code item)
        exit-ok (or (nil? exit-code) (= exit-code 0))]
    (str (c :yellow "  ⚡ shell ")
         (c :gray "$ ") cmd
         (when (and exit-code (not exit-ok))
           (c :red (str " [exit " exit-code "]")))
         (when-let [formatted (format-tool-result output opts)]
           (str "\n" (indent formatted 6))))))

(defn- format-codex-file-change [item]
  (let [changes (:changes item)]
    (str/join "\n"
              (map (fn [ch]
                     (let [kind (:kind ch)
                           path (:path ch)]
                       (str (case kind
                              "add" (c :green "  + ")
                              "delete" (c :red "  - ")
                              (c :yellow "  ~ "))
                            (c :gray path))))
                   changes))))

(defn- format-codex-turn-completed [record]
  (let [usage (:usage record)
        input (:input_tokens usage)
        cached (:cached_input_tokens usage)
        output (:output_tokens usage)]
    (when usage
      (c :gray (str "  tokens: in=" input
                     (when cached (str " cached=" cached))
                     " out=" output)))))

(defn- format-codex-record
  "Format a single Codex transcript record."
  [record opts]
  (let [type (:type record)
        item (:item record)]
    (case type
      "thread.started"
      (when-not (:no-system opts)
        (format-codex-session record))

      "turn.started" nil

      "turn.completed"
      (when-not (:no-system opts)
        (format-codex-turn-completed record))

      "item.started"
      nil ;; show on completed instead to include output

      "item.completed"
      (case (:type item)
        "agent_message"     (format-codex-agent-message item)
        "command_execution" (format-codex-command-completed item opts)
        "file_change"       (format-codex-file-change item)
        nil)

      nil)))

;;; Format detection

(defn- detect-format
  "Detect transcript format from the first record. Returns :claude or :codex."
  [first-line]
  (let [record (try (json/parse-string first-line true) (catch Exception _ nil))]
    (if (and record (contains? #{"thread.started" "turn.started" "item.started"
                                  "item.completed" "turn.completed"}
                               (:type record)))
      :codex
      :claude)))

;;; Main

(defn- format-record!
  "Format and print a single parsed JSONL record for the given format."
  [fmt record opts]
  (if (= fmt :codex)
    (when-let [out (format-codex-record record opts)]
      (println out))
    (let [type (:type record)
          subtype (:subtype record)]
      (cond
        ;; System events
        (= type "system")
        (cond
          (= subtype "init")
          (println (format-system-init record))

          (and (= subtype "hook_started") (not (:no-hooks opts)) (not (:no-system opts)))
          (println (format-system-hook record))

          (and (= subtype "hook_response") (not (:no-hooks opts)) (not (:no-system opts)))
          (println (format-hook-response record))

          :else nil)

        ;; Assistant messages
        (= type "assistant")
        (let [out (process-assistant-record record opts)]
          (when-not (str/blank? out)
            (println out)))

        ;; User messages (including tool results)
        (= type "user")
        (let [out (process-user-record record opts)]
          (when-not (str/blank? out)
            (println out)))

        ;; Rate limit
        (= type "rate_limit_event")
        (println (c :red (str "  ⏳ rate limited")))

        :else nil))))

(defn- parse-record [line]
  (try (json/parse-string line true) (catch Exception _ nil)))

(defn format-transcript
  "Read and format a JSONL transcript file."
  [path opts]
  (let [lines (vec (remove str/blank? (str/split-lines (slurp path))))
        fmt (detect-format (first lines))]
    (doseq [line lines
            :let [record (parse-record line)]
            :when record]
      (format-record! fmt record opts))))

(defn stream-transcript
  "Read JSONL records from stdin and format each as it arrives.
  Detects format from the first non-blank line; flushes after every record
  so output appears live when piped from `claude --output-format stream-json`."
  [opts]
  (let [fmt (volatile! nil)]
    (with-open [rdr (io/reader *in*)]
      (doseq [line (line-seq rdr)
              :when (not (str/blank? line))
              :let [_ (when-not @fmt (vreset! fmt (detect-format line)))
                    record (parse-record line)]
              :when record]
        (format-record! @fmt record opts)
        (flush)))))

(defn -main [args]
  (let [{:keys [opts args]} (cli/parse-args args {:spec cli-spec})
        path (first args)]
    (cond
      (:help opts)
      (print-usage)

      ;; Stream from stdin: explicit "-", --stdin flag, or no path given
      (or (:stdin opts) (= path "-") (nil? path))
      (stream-transcript opts)

      :else
      (do (when-not (fs/exists? path)
            (println (str "Error: file not found: " path))
            (System/exit 1))
          (format-transcript path opts)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
