FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update && apt-get install -y \
    curl git rlwrap jq nano unzip \
    && rm -rf /var/lib/apt/lists/*

# Clojure CLI
RUN curl -fsSL https://download.clojure.org/install/linux-install-1.12.0.1530.sh \
    -o /tmp/clj-install.sh \
    && chmod +x /tmp/clj-install.sh \
    && /tmp/clj-install.sh && rm /tmp/clj-install.sh

# clj-kondo
RUN curl -sLO https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo \
    && chmod +x install-clj-kondo && ./install-clj-kondo && rm install-clj-kondo

# Babashka
RUN curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install \
    && chmod +x install && ./install && rm install

# bbin (for clj-nrepl-eval)
RUN mkdir -p /root/.local/bin \
    && curl -o- -L https://raw.githubusercontent.com/babashka/bbin/v0.2.5/bbin > /root/.local/bin/bbin \
    && chmod +x /root/.local/bin/bbin
ENV PATH="/root/.local/bin:${PATH}"

# clj-nrepl-eval + clj-paren-repair-claude-hook (both from clojure-mcp-light)
RUN bbin install https://github.com/bhauman/clojure-mcp-light.git \
    --as clj-nrepl-eval \
    --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]' \
    && bbin install https://github.com/bhauman/clojure-mcp-light.git \
    --as clj-paren-repair-claude-hook \
    --main-opts '["-m" "clojure-mcp-light.hook"]'

# clojure-lsp
RUN curl -fsSL https://raw.githubusercontent.com/clojure-lsp/clojure-lsp/master/install \
    -o /tmp/clojure-lsp-install.sh \
    && chmod +x /tmp/clojure-lsp-install.sh \
    && /tmp/clojure-lsp-install.sh \
    && rm /tmp/clojure-lsp-install.sh

# Node.js + Claude Code
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y nodejs \
    && npm install -g @anthropic-ai/claude-code \
    && chmod +x /usr/lib/node_modules/@anthropic-ai/claude-code/vendor/ripgrep/*/rg

WORKDIR /work
