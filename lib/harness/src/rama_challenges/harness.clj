(ns rama-challenges.harness
  "Shared IPC lifecycle management for protocol-based Rama challenges.

   Challenges define a protocol and a `create-module` function returning
   {:module, :wrap-client}. This namespace provides
   `with-module` to handle IPC startup/shutdown and protocol client creation."
  (:require
   [clojure.test :as t]
   [com.rpl.rama.test :as rtest]))

(def ^:dynamic *task-count*
  "Number of tasks (partitions) used by `with-module` when launching IPC modules.
   Bound dynamically within `with-module` to a random value (2 or 4)."
  )

(defprotocol Synchronizable
  "Abstracts topology synchronization so tests work regardless of whether
   the implementation uses stream or microbatch topologies. Topology choice
   is a design decision the implementation makes on its own.

   Implementation:
   - For microbatch topologies: track the cumulative depot append count
     internally (e.g., in an atom incremented before each foreign-append!)
     and call (rtest/wait-for-microbatch-processed-count ipc module-name
     topology-name count) for each microbatch topology.
   - For stream topologies: no-op if all appends used full ack (the default).
   - For multiple topologies: wait on each one."
  (wait-for-processing! [this]
    "Waits for all pending topology processing to complete across all topologies."))

(defn clojure-fn-vars
  "Returns a seq of [name var] pairs for public vars in ns whose values
  are plain Clojure functions (clojure.lang.AFunction instances).
  deframafn vars produce SemiFunction instances and will not be included."
  [ns]
  (->> (ns-publics ns)
       (filterv (fn [[_ v]] (instance? clojure.lang.AFunction (var-get v))))))

(defn assert-no-clojure-fns
  "Asserts that the given namespace contains no public vars holding
  plain Clojure functions (defn/fn/#()). Only deframafn vars are allowed.
  ns-sym is a namespace symbol, e.g. 'my-challenge.solution."
  [ns-sym]
  (let [ns (the-ns ns-sym)
        bad-vars (clojure-fn-vars ns)]
    (t/is (empty? bad-vars)
          (str "expected no defn/fn vars in " ns-sym
               ", found: " (mapv first bad-vars)))))

(defmacro with-module
  "Lifecycle macro for running tests against a protocol-based Rama module.

   Calls `create-module-fn` to obtain {:module :wrap-client}.
   Starts an InProcessCluster, deploys the module, calls `:wrap-client` with
   the IPC to get a protocol implementation, binds it to `client-sym`, and
   executes `body`. Closes the IPC cluster in a finally block.

   Usage:
     (with-module [client create-my-module]
       (put-kv client \"k\" \"v\")
       (get-value client \"k\"))"
  [[client-sym create-module-fn] & body]
  `(let [result#      (~create-module-fn)
         module#      (:module result#)
         wrap-client# (:wrap-client result#)
         tasks#       (rand-nth [2 4])
         threads#     (+ 2 (rand-int (inc (- tasks# 2))))]
     (binding [*task-count* tasks#]
       (with-open [ipc# (rtest/create-ipc)]
         (rtest/launch-module! ipc# module# {:tasks tasks# :threads threads#})
         (let [~client-sym (wrap-client# ipc#)]
           ~@body)))))
