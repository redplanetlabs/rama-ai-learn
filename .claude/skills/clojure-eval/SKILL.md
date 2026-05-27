---
name: clojure-eval
description: Evaluate Clojure code via nREPL using clj-nrepl-eval. Use this when you need to test code, check if edited files compile, verify function behavior, or interact with a running REPL session.
---

# Clojure REPL Evaluation

λ discover_nrepl_ports(cwd) →
 bash("cd <cwd> && clj-nrepl-eval --discover-ports") →
 report([port, project-dir, runtime])

λ clojure_eval(port, code) →
 bash("clj-nrepl-eval --port <port> --code '<code>'") →
 normalize_output →
 response

λ connected_ports(x) → bash("clj-nrepl-eval --connected-ports")
λ reset_session(port) → bash("clj-nrepl-eval -p " + port + " --reset-session") → state(session,p) = ∅
