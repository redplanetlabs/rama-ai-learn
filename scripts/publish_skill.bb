#!/usr/bin/env bb

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def cli-spec
  {:remote  {:desc "Git remote URL for rama-skills"
             :alias :r
             :default "git@github.com:redplanetlabs/rama-skills.git"}
   :branch  {:desc "Branch name for the PR"
             :alias :b}
   :dry-run {:desc "Show what would be done without making changes"
             :alias :n
             :coerce :boolean}})

(def skill-source "plugins/rama-skill")

(defn sh
  "Run a shell command, returning stdout. Throws on failure."
  [& args]
  (let [result (apply p/shell {:out :string :err :string} args)]
    (str/trim (:out result))))

(defn generate-branch-name []
  (str "update-skill-" (sh "date" "+%Y%m%d")))

(defn copy-skill!
  "Copy only committed skill files from source repo into checkout."
  [project-root skill-prefix checkout-dir]
  (let [dest-skill (fs/path checkout-dir "plugins" "rama-skill")]
    (when (fs/exists? dest-skill)
      (fs/delete-tree dest-skill))
    (fs/create-dirs dest-skill)
    (p/shell {:dir project-root}
             "sh" "-c"
             (str "git archive HEAD " skill-prefix
                  " | tar -x -C " dest-skill " --strip-components="
                  (count (str/split skill-prefix #"/"))))
    (str dest-skill)))

(defn has-changes?
  "Check if the checkout has any changes."
  [dir]
  (not (str/blank? (sh "git" "-C" dir "status" "--porcelain"))))

(defn create-pr!
  "Create a PR on GitHub."
  [checkout-dir branch]
  (let [title (str "Update Rama skill - " (sh "date" "+%Y-%m-%d"))
        diff-stat (sh "git" "-C" checkout-dir "diff" "--stat" "HEAD~1")
        body (str "## Summary\n\n"
                  "Updated Rama skill files from rama-ai-learn.\n\n"
                  "## Changes\n\n```\n" diff-stat "\n```")]
    (println "Creating PR...")
    (let [pr-url (sh "gh" "pr" "create"
                      "--repo" "redplanetlabs/rama-skills"
                      "--title" title
                      "--body" body
                      "--base" "master"
                      "--head" branch)]
      (println "PR created:" pr-url)
      pr-url)))

(defn -main [args]
  (let [opts (cli/parse-opts args {:spec cli-spec})
        remote (:remote opts)
        branch (or (:branch opts) (generate-branch-name))
        dry-run? (:dry-run opts)
        project-root (str (fs/parent (fs/parent (fs/absolutize *file*))))
        tmpdir (str (fs/create-temp-dir {:prefix "rama-skills-"}))]
    (try
      (println "Source:" (str (fs/path project-root skill-source)) "(committed files only)")
      (println "Remote:" remote)
      (println "Branch:" branch)
      (println "Checkout:" tmpdir)

      (when dry-run?
        (println "\nDry run — no changes made.")
        (System/exit 0))

      ;; Shallow clone master into temp dir
      (println "\nCloning rama-skills...")
      (sh "git" "clone" "--depth" "1" "--branch" "master" remote tmpdir)

      ;; Create branch
      (println "Creating branch" branch "...")
      (sh "git" "-C" tmpdir "checkout" "-b" branch)

      ;; Copy committed skill files
      (println "Copying skill files...")
      (copy-skill! project-root skill-source tmpdir)

      ;; Check for changes
      (if-not (has-changes? tmpdir)
        (println "No changes detected — skill is already up to date.")
        (do
          (sh "git" "-C" tmpdir "add" "-A")
          (println "Changes:")
          (println (sh "git" "-C" tmpdir "diff" "--cached" "--stat"))
          (sh "git" "-C" tmpdir "commit" "-m" "update Rama skill from rama-ai-learn")

          (println "\nPushing to origin...")
          (sh "git" "-C" tmpdir "push" "-u" "origin" branch)

          (create-pr! tmpdir branch)

          (println "\nDone.")))
      (finally
        (fs/delete-tree tmpdir)))))

(-main *command-line-args*)
