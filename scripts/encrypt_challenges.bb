#!/usr/bin/env bb
;; Encrypt or decrypt test-resources and test-private files across challenges.
;;
;; Usage:
;;   bb encrypt-challenges [--challenge NAME] [--batch N]
;;   bb decrypt-challenges [--challenge NAME] [--batch N]
;;
;; Encryption key is read from the CHALLENGE_KEY env var.
;;
;; Encrypted files are written alongside the originals with an ".enc" suffix.
;; Decryption restores the originals from the ".enc" files.
;;
;; Each encrypted file contains a 16-byte random IV prepended to the AES-256-CBC
;; ciphertext, then the whole thing is Base64-encoded.

(require '[babashka.fs :as fs]
         '[babashka.cli :as cli]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(import '[javax.crypto Cipher]
        '[javax.crypto.spec SecretKeySpec IvParameterSpec]
        '[java.security MessageDigest SecureRandom]
        '[java.util Arrays Base64])

(def ^:private project-root
  (str (fs/parent (fs/parent (fs/absolutize *file*)))))

(def ^:private challenge-order-path
  (fs/path project-root "CHALLENGE_ORDER.md"))

;;; ── key derivation ──────────────────────────────────────────────────────────

(defn derive-key
  "Derive a 256-bit AES key from a passphrase via SHA-256."
  ^bytes [passphrase]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.digest md (.getBytes passphrase "UTF-8"))))

;;; ── encrypt / decrypt ───────────────────────────────────────────────────────

(defn encrypt-bytes
  "Encrypt `plaintext` bytes with AES-256-CBC using `key-bytes`.
   Returns Base64-encoded string of [16-byte IV || ciphertext]."
  ^String [^bytes key-bytes ^bytes plaintext]
  (let [iv     (byte-array 16)
        _      (.nextBytes (SecureRandom.) iv)
        spec   (SecretKeySpec. key-bytes "AES")
        cipher (Cipher/getInstance "AES/CBC/PKCS5Padding")]
    (.init cipher Cipher/ENCRYPT_MODE spec (IvParameterSpec. iv))
    (let [ciphertext  (.doFinal cipher plaintext)
          combined    (byte-array (+ 16 (alength ciphertext)))]
      (System/arraycopy iv 0 combined 0 16)
      (System/arraycopy ciphertext 0 combined 16 (alength ciphertext))
      (.encodeToString (Base64/getEncoder) combined))))

(defn decrypt-bytes
  "Decrypt a Base64-encoded string produced by `encrypt-bytes`.
   Returns the original plaintext bytes."
  ^bytes [^bytes key-bytes ^String b64]
  (let [combined   (.decode (Base64/getDecoder) b64)
        iv         (java.util.Arrays/copyOfRange combined 0 16)
        ciphertext (java.util.Arrays/copyOfRange combined 16 (alength combined))
        spec       (SecretKeySpec. key-bytes "AES")
        cipher     (Cipher/getInstance "AES/CBC/PKCS5Padding")]
    (.init cipher Cipher/DECRYPT_MODE spec (IvParameterSpec. iv))
    (.doFinal cipher ciphertext)))

;;; ── file helpers ────────────────────────────────────────────────────────────

(defn sensitive-dirs
  "Return existing sensitive dirs for a challenge: test-resources, test-private,
   test, and test-harness. Accepts either an absolute challenge directory path
   or a challenge name (resolved relative to `project-root`)."
  [challenge-name-or-path]
  (let [base (if (fs/absolute? (fs/path (str challenge-name-or-path)))
               (fs/path (str challenge-name-or-path))
               (fs/path project-root "challenges" (str challenge-name-or-path)))]
    (filterv fs/exists?
             [(fs/path base "test-resources")
              (fs/path base "test-private")
              (fs/path base "test")
              (fs/path base "test-harness")])))

(defn sensitive-src-test-files
  "Return test_support files in src/ that should also be encrypted."
  [challenge-name-or-path]
  (let [base (if (fs/absolute? (fs/path (str challenge-name-or-path)))
               (fs/path (str challenge-name-or-path))
               (fs/path project-root "challenges" (str challenge-name-or-path)))
        src-dir (fs/path base "src")]
    (when (fs/exists? src-dir)
      (filterv #(str/includes? (str (fs/file-name %)) "test")
               (filter fs/regular-file? (fs/glob src-dir "**"))))))

(defn- source-files
  "All non-encrypted files under the given dirs."
  [dirs]
  (mapcat (fn [d]
            (filter #(and (fs/regular-file? %)
                          (not (str/ends-with? (str (fs/file-name %)) ".enc")))
                    (fs/glob d "**")))
          dirs))

(defn- encrypted-files
  "All .enc files under the given dirs."
  [dirs]
  (mapcat (fn [d]
            (filter #(str/ends-with? (str (fs/file-name %)) ".enc")
                    (fs/glob d "**")))
          dirs))

(defn- file-permissions
  "Return POSIX file permissions for `path`, or nil when unsupported."
  [path]
  (try
    (java.nio.file.Files/getPosixFilePermissions
     (fs/path path)
     (make-array java.nio.file.LinkOption 0))
    (catch UnsupportedOperationException _ nil)
    (catch Exception _ nil)))

(defn- set-file-permissions!
  "Set POSIX file permissions for `path` when supported. No-op for nil perms."
  [path perms]
  (when perms
    (try
      (java.nio.file.Files/setPosixFilePermissions (fs/path path) perms)
      (catch UnsupportedOperationException _ nil)
      (catch Exception _ nil))))

(defn encrypt-file! [key-bytes path]
  (let [plain    (fs/read-all-bytes path)
        b64      (encrypt-bytes key-bytes plain)
        perms    (file-permissions path)
        enc-path (fs/path (str path ".enc"))]
    (spit (str enc-path) b64)
    (set-file-permissions! enc-path perms)
    (fs/delete path)
    enc-path))

(defn decrypt-file! [key-bytes enc-path]
  (let [b64      (str/trim (slurp (str enc-path)))
        plain    (decrypt-bytes key-bytes b64)
        perms    (file-permissions enc-path)
        out-path (fs/path (str/replace (str enc-path) #"\.enc$" ""))]
    (with-open [out (io/output-stream (str out-path))]
      (.write out plain))
    (set-file-permissions! out-path perms)
    (fs/delete enc-path)
    out-path))

(defn encrypt-challenge!
  "Encrypt all sensitive files for a challenge: test-resources, test-private,
   test, test-harness dirs, and test_support files in src/.
   `key-bytes` is a 32-byte AES key (see `derive-key`).
   `challenge-name` is a challenge name or absolute path.
   Returns the number of files encrypted."
  [key-bytes challenge-name]
  (let [dir-files (source-files (sensitive-dirs challenge-name))
        src-files (sensitive-src-test-files challenge-name)
        files     (concat dir-files src-files)]
    (doseq [f files]
      (encrypt-file! key-bytes f))
    (count files)))

(defn decrypt-challenge!
  "Decrypt all .enc files in sensitive dirs and src/ test files for a challenge.
   `key-bytes` is a 32-byte AES key (see `derive-key`).
   `challenge-name` is a challenge name or absolute path.
   Returns the number of files decrypted."
  [key-bytes challenge-name]
  (let [dir-files (encrypted-files (sensitive-dirs challenge-name))
        src-dir   (let [base (if (fs/absolute? (fs/path (str challenge-name)))
                               (fs/path (str challenge-name))
                               (fs/path project-root "challenges" (str challenge-name)))]
                    (fs/path base "src"))
        src-files (when (fs/exists? src-dir)
                    (filterv #(str/ends-with? (str (fs/file-name %)) ".enc")
                             (filter fs/regular-file? (fs/glob src-dir "**"))))
        files     (concat dir-files src-files)]
    (doseq [f files]
      (decrypt-file! key-bytes f))
    (count files)))

;;; ── challenge order ─────────────────────────────────────────────────────────

(defn- parse-challenge-order [path]
  (let [lines (str/split-lines (slurp (str path)))]
    (loop [lines lines batch nil result []]
      (if-let [line (first lines)]
        (let [bm (re-matches #"## Batch (\d+):.*" line)
              cm (re-matches #"- (.+)" (str/trim line))]
          (cond
            bm (recur (rest lines) (parse-long (second bm)) result)
            (and cm batch) (recur (rest lines) batch
                                  (conj result {:name (second cm) :batch batch}))
            :else (recur (rest lines) batch result)))
        result))))

(defn- select-challenges
  "Return challenge names matching the given opts."
  [{:keys [challenge batch]}]
  (let [all (parse-challenge-order challenge-order-path)]
    (cond
      challenge (if (some #(= challenge (:name %)) all)
                  [challenge]
                  (do (println "Unknown challenge:" challenge) (System/exit 1)))
      batch     (let [selected (mapv :name (filter #(= batch (:batch %)) all))]
                  (if (seq selected)
                    selected
                    (do (println "No challenges in batch" batch) (System/exit 1))))
      :else     (mapv :name all))))

;;; ── main ops ────────────────────────────────────────────────────────────────

(defn- require-key []
  (or (System/getenv "CHALLENGE_KEY")
      (do (println "Error: CHALLENGE_KEY env var is not set.")
          (System/exit 1))))

(defn- run-encrypt! [opts]
  (let [key-bytes  (derive-key (require-key))
        names      (select-challenges opts)
        file-count (atom 0)]
    (doseq [name names]
      (let [n (encrypt-challenge! key-bytes name)]
        (when (pos? n)
          (println (str "Encrypting " name " (" n " file(s))"))
          (swap! file-count + n))))
    (println (str "\nDone. " @file-count " file(s) encrypted."))))

(defn- run-decrypt! [opts]
  (let [key-bytes  (derive-key (require-key))
        names      (select-challenges opts)
        file-count (atom 0)]
    (doseq [name names]
      (let [n (decrypt-challenge! key-bytes name)]
        (when (pos? n)
          (println (str "Decrypting " name " (" n " file(s))"))
          (swap! file-count + n))))
    (println (str "\nDone. " @file-count " file(s) decrypted."))))

;;; ── CLI entry point ─────────────────────────────────────────────────────────

(def ^:private cli-spec
  {:challenge {:desc    "Single challenge name to act on"
               :alias   :c}
   :batch     {:desc    "Batch number to act on (1-9)"
               :alias   :b
               :coerce  :int}
   :help      {:desc    "Show usage"
               :alias   :h
               :coerce  :boolean}})

(defn -main [args]
  (let [parsed (cli/parse-opts args {:spec cli-spec})
        ;; mode is injected by bb.edn as `challenge-crypt-mode` before load-file
        mode   (resolve 'challenge-crypt-mode)]
    (when (:help parsed)
      (println "Usage: bb encrypt-challenges|decrypt-challenges [options]")
      (println)
      (println "Options:")
      (println "  -c, --challenge NAME   Act on a single challenge")
      (println "  -b, --batch N          Act on all challenges in a batch (1-9)")
      (println "  -h, --help             Show this message")
      (println)
      (println "Env vars:")
      (println "  CHALLENGE_KEY          Passphrase used to derive the AES-256 key")
      (System/exit 0))
    (case (deref mode)
      :encrypt (run-encrypt! parsed)
      :decrypt (run-decrypt! parsed)
      (do (println "Internal error: unknown mode" (deref mode)) (System/exit 1)))))
