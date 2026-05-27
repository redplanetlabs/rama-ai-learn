(ns rama-challenges.event-assertions
  "Declarative assertions for ordered event-stream matching in challenge tests."
  (:require
   [clojure.string :as str]
   [clojure.test :as t]))

(defn- var-symbol?
  [x]
  (and (symbol? x)
       (str/starts-with? (name x) "?")))

(defn- bind-value
  [bindings v actual]
  (if (var-symbol? v)
    (if (contains? bindings v)
      (when (= (get bindings v) actual)
        bindings)
      (assoc bindings v actual))
    (when (= v actual)
      bindings)))

(defn- match-entry
  [bindings event [k v]]
  (bind-value bindings v (get event k)))

(defn- match-bindings
  [bindings event bind-spec]
  (reduce (fn [bs [binding-sym event-key]]
            (when bs
              (bind-value bs binding-sym (get event event-key))))
          bindings
          bind-spec))

(defn- match-event
  [bindings pattern event]
  (let [{:keys [bind]} pattern
        attrs (dissoc pattern :bind)]
    (when-let [bindings' (reduce (fn [bs entry]
                                   (when bs
                                     (match-entry bs event entry)))
                                 bindings
                                 attrs)]
      (match-bindings bindings' event bind))))

(defn- find-next-match
  [events start pattern bindings]
  (loop [idx start]
    (when (< idx (count events))
      (let [event (nth events idx)]
        (if-let [bindings' (match-event bindings pattern event)]
          {:idx idx :event event :bindings bindings'}
          (recur (inc idx)))))))

(defn match-ordered
  "Attempts to match `patterns` against `events` as an ordered subsequence.

   Pattern maps match partially against events. Symbol values beginning with `?`
   are treated as logic vars and must unify across later patterns.

   `:bind` can be used to bind vars from event keys, e.g.
     {:event-type :partitioner :bind {?task-id :to-task-id}}

   Returns {:ok? true ...} on success, otherwise {:ok? false ...}."
  [events patterns]
  (loop [remaining patterns
         start 0
         bindings {}
         matches []]
    (if-let [pattern (first remaining)]
      (if-let [{:keys [idx event bindings] :as match}
               (find-next-match events start pattern bindings)]
        (recur (next remaining) (inc idx) bindings (conj matches match))
        {:ok? false
         :failed-pattern pattern
         :bindings bindings
         :matches matches
         :events events})
      {:ok? true
       :bindings bindings
       :matches matches
       :events events})))

(defn assert-ordered!
  "Asserts that `patterns` match `events` as an ordered subsequence."
  [events patterns]
  (let [{:keys [ok? failed-pattern bindings matches] :as result}
        (match-ordered events patterns)]
    (t/is ok?
          (str "expected ordered event pattern match"
               "\npatterns: " (pr-str patterns)
               "\nfailed-pattern: " (pr-str failed-pattern)
               "\nbindings: " (pr-str bindings)
               "\nmatches: " (pr-str (mapv (fn [{:keys [idx event]}]
                                            {:idx idx :event event})
                                          matches))
               "\nevents: " (pr-str events)))
    result))
