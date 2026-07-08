#!/usr/bin/env bb

(require '[clojure.test :refer [deftest testing is run-tests]])

;; Load the runner script to get access to its functions
(load-file "scripts/run_challenges.bb")

(deftest format-duration-test
  ;; Tests the format-duration function which converts elapsed seconds
  ;; into human-readable duration strings.
  ;; Contract: >= 60s → "Xm Ys", < 60s → "Xs", fractional → rounded
  (testing "format-duration"
    (testing "when given zero seconds"
      (is (= "0s" (format-duration 0))))
    (testing "when given sub-minute values"
      (is (= "45s" (format-duration 45))))
    (testing "when given exactly 60 seconds"
      (is (= "1m 0s" (format-duration 60))))
    (testing "when given multi-minute values"
      (is (= "3m 42s" (format-duration 222))))
    (testing "when given fractional seconds"
      (is (= "31s" (format-duration 30.6)))
      (is (= "30s" (format-duration 30.4))))
    (testing "when rounding crosses the 60s boundary"
      (is (= "1m 0s" (format-duration 59.5))))))

(def sample-results
  [{:name "challenge-a" :status :pass :iterations 1 :duration-s 10
    :input-tokens 100 :output-tokens 50 :cache-creation-tokens 10 :cache-read-tokens 80
    :skills-used ["rama" "rama-app-design"]
    :skill-refs-used ["paths.md" "task-globals.md"]
    :scoring {:scores {:alignment 5}
              :composite 5.0}}
   {:name "challenge-b" :status :fail :iterations 3 :duration-s 50
    :input-tokens 200 :output-tokens 75 :cache-creation-tokens 20 :cache-read-tokens 150
    :skills-used ["rama"]
    :skill-refs-used []
    :scoring nil}])

(deftest parse-token-usage-test
  ;; Tests parsing of token usage from Claude's NDJSON output.
  ;; Contract: sums usage fields across result-type messages, defaults to 0
  ;; for missing fields, handles malformed/empty input gracefully.
  (testing "parse-token-usage"
    (testing "when given valid NDJSON with multiple result messages"
      (let [output (str "{\"type\":\"result\",\"usage\":{\"input_tokens\":100,\"output_tokens\":50,\"cache_creation_input_tokens\":10,\"cache_read_input_tokens\":80}}\n"
                        "{\"type\":\"result\",\"usage\":{\"input_tokens\":200,\"output_tokens\":75,\"cache_creation_input_tokens\":20,\"cache_read_input_tokens\":150}}\n")]
        (is (= {:input-tokens 300
                :output-tokens 125
                :cache-creation-tokens 30
                :cache-read-tokens 230}
               (parse-token-usage output)))))

    (testing "when given mixed message types"
      (let [output (str "{\"type\":\"assistant\",\"content\":\"hello\"}\n"
                        "{\"type\":\"result\",\"usage\":{\"input_tokens\":50,\"output_tokens\":25,\"cache_creation_input_tokens\":5,\"cache_read_input_tokens\":40}}\n"
                        "{\"type\":\"tool_use\",\"name\":\"bash\"}\n")]
        (is (= {:input-tokens 50
                :output-tokens 25
                :cache-creation-tokens 5
                :cache-read-tokens 40}
               (parse-token-usage output)))))

    (testing "when result message has no usage field"
      (let [output "{\"type\":\"result\",\"content\":\"done\"}\n"]
        (is (= {:input-tokens 0
                :output-tokens 0
                :cache-creation-tokens 0
                :cache-read-tokens 0}
               (parse-token-usage output)))))

    (testing "when given empty input"
      (is (= {:input-tokens 0
              :output-tokens 0
              :cache-creation-tokens 0
              :cache-read-tokens 0}
             (parse-token-usage ""))))

    (testing "when given nil input"
      (is (= {:input-tokens 0
              :output-tokens 0
              :cache-creation-tokens 0
              :cache-read-tokens 0}
             (parse-token-usage nil))))

    (testing "when given non-JSON lines"
      (let [output "not json\n{\"type\":\"result\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_creation_input_tokens\":1,\"cache_read_input_tokens\":8}}\ngarbage\n"]
        (is (= {:input-tokens 10
                :output-tokens 5
                :cache-creation-tokens 1
                :cache-read-tokens 8}
               (parse-token-usage output)))))

    (testing "when usage has partial fields"
      (let [output "{\"type\":\"result\",\"usage\":{\"input_tokens\":10}}\n"]
        (is (= {:input-tokens 10
                :output-tokens 0
                :cache-creation-tokens 0
                :cache-read-tokens 0}
               (parse-token-usage output)))))

    (testing "when given a single JSON object (--output-format json)"
      (let [output "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"abc\",\"usage\":{\"input_tokens\":3,\"cache_creation_input_tokens\":22042,\"cache_read_input_tokens\":0,\"output_tokens\":5}}"]
        (is (= {:input-tokens 3
                :output-tokens 5
                :cache-creation-tokens 22042
                :cache-read-tokens 0}
               (parse-token-usage output)))))

    (testing "when given Codex JSONL with turn.completed events"
      (let [output (str "{\"type\":\"thread.started\",\"thread_id\":\"abc\"}\n"
                        "{\"type\":\"turn.started\"}\n"
                        "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"cached_input_tokens\":40,\"output_tokens\":50}}\n")]
        (is (= {:input-tokens 100
                :output-tokens 50
                :cache-creation-tokens 0
                :cache-read-tokens 40}
               (parse-token-usage output)))))

    (testing "when given Codex JSONL with multiple turn.completed events"
      (let [output (str "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"cached_input_tokens\":40,\"output_tokens\":50}}\n"
                        "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":200,\"cached_input_tokens\":80,\"output_tokens\":75}}\n")]
        (is (= {:input-tokens 300
                :output-tokens 125
                :cache-creation-tokens 0
                :cache-read-tokens 120}
               (parse-token-usage output)))))))

(deftest token-totals-test
  ;; Tests that token-totals sums token fields across result maps,
  ;; handling nil values gracefully.
  (testing "token-totals"
    (testing "sums token fields across results"
      (is (= {:input-tokens 300
              :output-tokens 125
              :cache-creation-tokens 30
              :cache-read-tokens 230}
             (token-totals sample-results))))
    (testing "returns zeros for empty results"
      (is (= {:input-tokens 0
              :output-tokens 0
              :cache-creation-tokens 0
              :cache-read-tokens 0}
             (token-totals []))))
    (testing "treats nil token values as zero"
      (is (= {:input-tokens 100
              :output-tokens 50
              :cache-creation-tokens 10
              :cache-read-tokens 80}
             (token-totals [{:name "a" :input-tokens 100 :output-tokens 50
                             :cache-creation-tokens 10 :cache-read-tokens 80}
                            {:name "b"}]))))))

(deftest parse-skills-used-test
  ;; Tests extraction of skill names from Claude NDJSON transcripts.
  ;; Contract: returns sorted, deduplicated skill names from Skill tool_use blocks.
  (testing "parse-skills-used"
    (testing "when transcript contains Skill tool uses"
      (let [output (str/join "\n"
                    ["{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Skill\",\"input\":{\"skill\":\"rama\"}}]}}"
                     "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/tmp/x\"}}]}}"
                     "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Skill\",\"input\":{\"skill\":\"rama-app-design\"}}]}}"])]
        (is (= ["rama" "rama-app-design"] (parse-skills-used output)))))
    (testing "when same skill is used multiple times"
      (let [output (str/join "\n"
                    ["{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Skill\",\"input\":{\"skill\":\"rama\"}}]}}"
                     "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Skill\",\"input\":{\"skill\":\"rama\"}}]}}"])]
        (is (= ["rama"] (parse-skills-used output)))))
    (testing "when no skills are used"
      (let [output "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{}}]}}"]
        (is (= [] (parse-skills-used output)))))
    (testing "when Codex reads SKILL.md from skills directory"
      (let [output (str "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"sed -n '1,220p' .codex/skills/rama-challenges/SKILL.md\\\"\"}}\n"
                        "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"sed -n '1,260p' plugins/rama-skill/skills/rama/SKILL.md\\\"\"}}\n"
                        "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"sed -n '1,240p' plugins/rama-skill/skills/rama-app-design/SKILL.md\\\"\"}}\n")]
        (is (= ["rama" "rama-app-design" "rama-challenges"] (parse-skills-used output)))))
    (testing "when Codex reads same skill multiple times"
      (let [output (str "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"sed -n '1,260p' plugins/rama-skill/skills/rama/SKILL.md\\\"\"}}\n"
                        "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"sed -n '680,760p' plugins/rama-skill/skills/rama/SKILL.md\\\"\"}}\n")]
        (is (= ["rama"] (parse-skills-used output)))))
    (testing "when given empty input"
      (is (= [] (parse-skills-used ""))))
    (testing "when given nil input"
      (is (= [] (parse-skills-used nil))))))

(deftest parse-skill-refs-used-test
  ;; Tests extraction of skill reference filenames from agent transcripts.
  ;; Contract: returns sorted, deduplicated reference filenames from tool_use
  ;; blocks that access references/*.md files.
  (testing "parse-skill-refs-used"
    (testing "when Claude reads a reference file via Read tool"
      (let [output (str "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/.claude/skills/rama/references/task-globals.md\"}}]}}\n")]
        (is (= ["task-globals.md"] (parse-skill-refs-used output)))))
    (testing "when multiple different references are accessed"
      (let [output (str/join "\n"
                    ["{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/skills/rama/references/paths.md\"}}]}}"
                     "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/skills/rama/references/aggregators.md\"}}]}}"])]
        (is (= ["aggregators.md" "paths.md"] (parse-skill-refs-used output)))))
    (testing "when same reference is read multiple times"
      (let [output (str/join "\n"
                    ["{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/references/paths.md\"}}]}}"
                     "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/references/paths.md\"}}]}}"])]
        (is (= ["paths.md"] (parse-skill-refs-used output)))))
    (testing "when Grep searches within a reference file"
      (let [output "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Grep\",\"input\":{\"path\":\"/project/references/pstate-schema.md\",\"pattern\":\"subindex\"}}]}}\n"]
        (is (= ["pstate-schema.md"] (parse-skill-refs-used output)))))
    (testing "when no references are accessed"
      (let [output "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/project/src/my/module.clj\"}}]}}\n"]
        (is (= [] (parse-skill-refs-used output)))))
    (testing "when Codex reads a reference file"
      (let [output "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc \\\"cat plugins/rama-skill/skills/rama/references/testing.md\\\"\"}}\n"]
        (is (= ["testing.md"] (parse-skill-refs-used output)))))
    (testing "when given empty input"
      (is (= [] (parse-skill-refs-used ""))))
    (testing "when given nil input"
      (is (= [] (parse-skill-refs-used nil))))))

(deftest print-summary-table-test
  ;; Tests that print-summary-table outputs token columns, token totals,
  ;; and elapsed time when provided.
  ;; Contract: table includes token columns and totals; total-elapsed-s
  ;; nil → no elapsed line, non-nil → "Total elapsed: ..." line
  (testing "print-summary-table"
    (testing "when total-elapsed-s is provided"
      (let [output (with-out-str (print-summary-table sample-results 222))]
        (is (re-find #"Total elapsed: 3m 42s" output)
            "should include formatted elapsed time")))
    (testing "when total-elapsed-s is nil"
      (let [output (with-out-str (print-summary-table sample-results nil))]
        (is (not (re-find #"Total elapsed" output))
            "should not include elapsed time line")))
    (testing "when called with single arity"
      (let [output (with-out-str (print-summary-table sample-results))]
        (is (not (re-find #"Total elapsed" output))
            "should not include elapsed time line")))
    (testing "includes token, skills, and score column headers"
      (let [output (with-out-str (print-summary-table sample-results))]
        (is (re-find #"In Tokens" output))
        (is (re-find #"Out Tokens" output))
        (is (re-find #"Cache Create" output))
        (is (re-find #"Cache Read" output))
        (is (re-find #"Skills" output))
        (is (re-find #"Align" output))))
    (testing "includes per-challenge token values and skills"
      (let [output (with-out-str (print-summary-table sample-results))]
        (is (re-find #"challenge-a.*100" output))
        (is (re-find #"challenge-b.*200" output))
        (is (re-find #"rama, rama-app-design" output))
        (is (re-find #"challenge-b.*rama" output))))
    (testing "includes token totals in summary"
      (let [output (with-out-str (print-summary-table sample-results))]
        (is (re-find #"Tokens: In: 300 \| Out: 125 \| Cache Create: 30 \| Cache Read: 230" output))))
    (testing "includes average score and alignment"
      (let [output (with-out-str (print-summary-table sample-results))]
        (is (re-find #"Average score:" output))
        (is (re-find #"Average alignment: 5\.0/5" output))))
    (testing "when no results have alignment scores"
      (let [unscored [{:name "ch" :status :fail :iterations 1 :duration-s 5
                        :input-tokens 10 :output-tokens 5
                        :cache-creation-tokens 0 :cache-read-tokens 0
                        :challenge-score 0
                        :skills-used [] :scoring nil}]
            output (with-out-str (print-summary-table unscored))]
        (is (not (re-find #"Average alignment" output)))))))

(deftest generate-report-test
  ;; Tests that generate-report includes token columns, token totals,
  ;; and elapsed time in the markdown report.
  ;; Contract: table has token columns; footer has token totals;
  ;; total-elapsed-s nil → no elapsed line, non-nil → "**Total elapsed:** ..." line
  (testing "generate-report"
    (let [tmp-root (str (babashka.fs/create-temp-dir))
          project-dir (str (babashka.fs/path tmp-root "project"))]
      (babashka.fs/create-dirs project-dir)
      (try
        (testing "when total-elapsed-s is provided"
          (let [path (generate-report sample-results "test" project-dir {:total-elapsed-s 222})
                content (slurp path)]
            (is (re-find #"\*\*Total elapsed:\*\* 3m 42s" content)
                "should include formatted elapsed time")))
        (testing "when total-elapsed-s is nil"
          (let [path (generate-report sample-results "test" project-dir {})
                content (slurp path)]
            (is (not (re-find #"Total elapsed" content))
                "should not include elapsed time")))
        (testing "when called with three-arity"
          (let [path (generate-report sample-results "test" project-dir)
                content (slurp path)]
            (is (not (re-find #"Total elapsed" content))
                "should not include elapsed time")))
        (testing "includes token, skills, and score column headers in markdown table"
          (let [path (generate-report sample-results "test" project-dir)
                content (slurp path)]
            (is (re-find #"In Tokens" content))
            (is (re-find #"Out Tokens" content))
            (is (re-find #"Cache Create" content))
            (is (re-find #"Cache Read" content))
            (is (re-find #"Skills" content))
            (is (re-find #"Align" content))))
        (testing "includes per-challenge token values and skills"
          (let [path (generate-report sample-results "test" project-dir)
                content (slurp path)]
            (is (re-find #"challenge-a.*100.*50.*10.*80" content))
            (is (re-find #"challenge-b.*200.*75.*20.*150" content))
            (is (re-find #"rama, rama-app-design" content))))
        (testing "includes token totals in footer"
          (let [path (generate-report sample-results "test" project-dir)
                content (slurp path)]
            (is (re-find #"\*\*Tokens:\*\* In: 300 \| Out: 125 \| Cache Create: 30 \| Cache Read: 230" content))))
        (testing "includes average score and alignment in footer"
          (let [path (generate-report sample-results "test" project-dir)
                content (slurp path)]
            (is (re-find #"\*\*Average score:\*\*" content))
            (is (re-find #"\*\*Average alignment:\*\* 5\.0/5" content))))
        (testing "when model is specified"
          (let [path (generate-report sample-results "claude" project-dir {:total-elapsed-s 100 :model "sonnet"})
                content (slurp path)]
            (is (re-find #"-claude-sonnet\.md$" path)
                "filename should include model")
            (is (re-find #"Model: sonnet" content)
                "content should include Model line")))
        (testing "when model is nil"
          (let [path (generate-report sample-results "claude" project-dir {:total-elapsed-s 100})
                content (slurp path)]
            (is (re-find #"-claude\.md$" path)
                "filename should not include model")
            (is (not (re-find #"Model:" content))
                "content should not include Model line")))
        (testing "when reasoning is specified with model"
          (let [path (generate-report sample-results "claude" project-dir {:total-elapsed-s 100 :model "sonnet" :reasoning "high"})
                content (slurp path)]
            (is (re-find #"-claude-sonnet-high\.md$" path)
                "filename should include model and reasoning")
            (is (re-find #"Reasoning: high" content)
                "content should include Reasoning line")))
        (testing "when reasoning is specified without model"
          (let [path (generate-report sample-results "claude" project-dir {:total-elapsed-s 100 :reasoning "medium"})
                content (slurp path)]
            (is (re-find #"-claude\.md$" path)
                "filename should not include reasoning when model is absent")
            (is (re-find #"Reasoning: medium" content)
                "content should include Reasoning line")))
        (finally
          (babashka.fs/delete-tree tmp-root))))))

(deftest generate-report-location-test
  ;; Tests that generate-report writes files under ../reports
  ;; relative to the provided project root.
  (testing "generate-report stores report under ../reports"
    (let [tmp-root (str (babashka.fs/create-temp-dir))
          project-dir (str (babashka.fs/path tmp-root "project"))]
      (babashka.fs/create-dirs project-dir)
      (try
        (let [path (generate-report sample-results "test" project-dir)
              expected-dir (-> (babashka.fs/path project-dir ".." "reports")
                               babashka.fs/normalize
                               str)
              actual-dir (-> path
                             babashka.fs/parent
                             babashka.fs/normalize
                             str)]
          (is (= expected-dir actual-dir)
              "report should be written under ../reports"))
        (finally
          (babashka.fs/delete-tree tmp-root))))))

(deftest discover-all-challenges-test
  ;; Tests that challenge discovery finds runnable challenge directories even
  ;; when they are absent from CHALLENGE_ORDER.md, while excluding templates
  ;; and shared support dirs.
  (testing "discover-all-challenges"
    (let [tmp-root (str (babashka.fs/create-temp-dir))
          project-dir (str (babashka.fs/path tmp-root "project"))
          challenges-dir (babashka.fs/path project-dir "challenges")]
      (babashka.fs/create-dirs challenges-dir)
      (doseq [name ["listed-challenge"
                    "unlisted-challenge"
                    "unlisted-hard"]]
        (let [dir (babashka.fs/path challenges-dir name)]
          (babashka.fs/create-dirs dir)
          (spit (str (babashka.fs/path dir "README.md")) (str "# " name "\n"))))
      (try
        (let [ordered [{:name "listed-challenge" :batch 2 :difficulty :standard}]
              discovered (discover-all-challenges project-dir ordered)
              by-name (into {} (map (juxt :name identity) discovered))]
          (is (= #{"listed-challenge" "unlisted-challenge" "unlisted-hard"}
                 (set (keys by-name))))
          (is (= {:name "listed-challenge" :batch 2 :difficulty :standard}
                 (get by-name "listed-challenge"))
              "should preserve metadata from CHALLENGE_ORDER")
          (is (= {:name "unlisted-challenge" :batch nil :difficulty :standard}
                 (get by-name "unlisted-challenge"))
              "should infer standard difficulty for unlisted challenges")
          (is (= {:name "unlisted-hard" :batch nil :difficulty :hard}
                 (get by-name "unlisted-hard"))
              "should infer hard difficulty from -hard suffix"))
        (finally
          (babashka.fs/delete-tree tmp-root))))))

(deftest workflow-cmd-test
  (testing "workflow-cmd"
    (testing "uses stream-json output format and /rama-challenge prompt"
      (let [cmd (workflow-cmd "test-ch" nil nil)]
        (is (some #{"stream-json"} cmd) "should use stream-json format")
        (is (some #{"--verbose"} cmd) "should include --verbose")
        (is (some #{"/rama-challenge test-ch"} cmd)
            "should pass the /rama-challenge prompt with name")))
    (testing "when model and reasoning are nil"
      (let [cmd (workflow-cmd "test-ch" nil nil)]
        (is (not (some #{"--model"} cmd)) "should not contain --model")
        (is (not (some #{"--effort"} cmd)) "should not contain --effort")))
    (testing "when model is specified"
      (let [cmd (workflow-cmd "test-ch" "sonnet" nil)]
        (is (some #{"--model"} cmd) "should contain --model")
        (is (some #{"sonnet"} cmd) "should contain model value")))
    (testing "when reasoning is specified"
      (let [cmd (workflow-cmd "test-ch" nil "high")]
        (is (some #{"--effort"} cmd) "should contain --effort")
        (is (some #{"high"} cmd) "should contain reasoning value")))
    (testing "when both model and reasoning are specified"
      (let [cmd (workflow-cmd "test-ch" "sonnet" "medium")]
        (is (some #{"--model"} cmd) "should contain --model")
        (is (some #{"--effort"} cmd) "should contain --effort")))))

(deftest reasoning-cli-parsing-test
  ;; Tests that --reasoning / -r is parsed by cli-spec and produces
  ;; :reasoning "LEVEL" in the opts map.
  (testing "--reasoning CLI option"
    (testing "when --reasoning is passed"
      (let [opts (cli/parse-opts ["--reasoning" "high" "--agent" "claude"] {:spec cli-spec})]
        (is (= "high" (:reasoning opts))
            "should set :reasoning to the provided value")))
    (testing "when -r short form is passed"
      (let [opts (cli/parse-opts ["-r" "low" "--agent" "claude"] {:spec cli-spec})]
        (is (= "low" (:reasoning opts))
            "should set :reasoning via alias")))
    (testing "when --reasoning is not passed"
      (let [opts (cli/parse-opts ["--agent" "claude"] {:spec cli-spec})]
        (is (nil? (:reasoning opts))
            "should leave :reasoning absent")))))

(deftest print-run-header-model-test
  ;; Tests that print-run-header shows model in parentheses when provided
  ;; and the effort label inline. Format: "Agent: <name>[ (<model>)] [effort: <level>] | ...".
  (testing "print-run-header"
    (testing "when model is specified"
      (let [output (with-out-str (print-run-header "claude" 5 {} "sonnet" nil))]
        (is (re-find #"Agent: claude \(sonnet\)" output)
            "should show model in parentheses")))
    (testing "when model is nil"
      (let [output (with-out-str (print-run-header "claude" 5 {} nil nil))]
        (is (re-find #"Agent: claude \[effort:" output)
            "should not show parentheses around the agent name")
        (is (not (re-find #"Agent: claude \(" output))
            "should have no parentheses immediately after agent name")))
    (testing "when reasoning is specified with model"
      (let [output (with-out-str (print-run-header "claude" 5 {} "sonnet" "high"))]
        (is (re-find #"Agent: claude \(sonnet\)" output)
            "should show model in parentheses")
        (is (re-find #"\[effort: high\]" output)
            "should show effort inline")))
    (testing "when reasoning is specified without model"
      (let [output (with-out-str (print-run-header "claude" 5 {} nil "medium"))]
        (is (re-find #"\[effort: medium\]" output)
            "should show effort inline")))))

(deftest save-transcript-test
  ;; Tests that save-transcript! writes content under ../transcripts relative
  ;; to project root, encodes phase-id and (when > 1) attempt in the filename,
  ;; and uses run-start-time for the {date}-{time} prefix so all transcripts of
  ;; one challenge run share that prefix.
  (testing "save-transcript!"
    (let [tmp-root (str (babashka.fs/create-temp-dir))
          project-dir (str (babashka.fs/path tmp-root "project"))
          run-start (java.time.LocalDateTime/now)]
      (babashka.fs/create-dirs project-dir)
      (try
        (testing "writes content and returns path"
          (let [path (save-transcript! project-dir "claude" nil nil "my-challenge"
                                       "jsonl content" 0 1 run-start)]
            (is (= "jsonl content" (slurp path)))
            (is (re-find #"my-challenge-phase0\.jsonl$" path))))
        (testing "path is under ../transcripts"
          (let [path (save-transcript! project-dir "claude" nil nil "ch" "x" 0 1 run-start)
                expected-dir (-> (babashka.fs/path project-dir ".." "transcripts")
                                 babashka.fs/normalize str)
                actual-dir   (-> path babashka.fs/parent babashka.fs/normalize str)]
            (is (= expected-dir actual-dir))))
        (testing "includes model in filename when provided"
          (let [path (save-transcript! project-dir "claude" "sonnet" nil "ch" "x" 0 1 run-start)]
            (is (re-find #"-claude-sonnet-ch-phase0\.jsonl$" path))))
        (testing "includes model and reasoning in filename when both provided"
          (let [path (save-transcript! project-dir "claude" "sonnet" "high" "ch" "x" 0 1 run-start)]
            (is (re-find #"-claude-sonnet-high-ch-phase0\.jsonl$" path))))
        (testing "encodes phase-id and attempt when attempt > 1"
          (let [path (save-transcript! project-dir "claude" nil nil "ch" "x" 3 2 run-start)]
            (is (re-find #"-ch-phase3-attempt2\.jsonl$" path))))
        (testing "all phases of one run share the {date}-{time} prefix"
          (let [p0 (save-transcript! project-dir "claude" nil nil "ch" "x" 0 1 run-start)
                p3 (save-transcript! project-dir "claude" nil nil "ch" "x" 3 1 run-start)
                prefix (fn [p] (-> p babashka.fs/file-name str
                                   (clojure.string/replace #"-ch-phase\d+(-attempt\d+)?\.jsonl$" "")))]
            (is (= (prefix p0) (prefix p3)))))
        (finally
          (babashka.fs/delete-tree tmp-root))))))

(deftest parse-workflow-verdict-test
  (testing "returns :pass when PASS marker present"
    (is (= :pass (parse-workflow-verdict "DONE [my-ch]: PASS — test suite green"))))
  (testing "returns :fail when no PASS marker"
    (is (= :fail (parse-workflow-verdict "DONE [my-ch]: INCOMPLETE — see Phase 7 summary")))
    (is (= :fail (parse-workflow-verdict "some random output")))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
