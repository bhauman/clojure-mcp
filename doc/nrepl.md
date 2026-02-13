# nREPL Setup (Claude Desktop)

You generally only need to set up and run nREPL explicitly when using **Claude Desktop** (or other desktop chat apps) because they start MCP servers outside your project directory.

## deps.edn

Add an `:nrepl` alias to your project's `deps.edn`:

```clojure
{:aliases
 {:nrepl
  {:extra-paths ["test"]
   :extra-deps {nrepl/nrepl {:mvn/version "1.3.1"}}
   :main-opts ["-m" "nrepl.cmdline" "--port" "7888"]}}}
```

Start the nREPL server from your project directory:

```bash
clojure -M:nrepl
```

## Leiningen

Start an nREPL server from your project directory:

```bash
lein repl :headless :port 7888
```

## Port Notes

- `7888` is just an example. Any open port is fine as long as you use the same port when starting ClojureMCP (for Claude Desktop).
