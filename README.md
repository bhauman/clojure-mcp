# Clojure MCP: A Live REPL for Your Coding Agent

ClojureMCP gives coding agents like Claude Code and Codex a persistent
connection to your Clojure REPL.

## Table of Contents

- [What is ClojureMCP?](#what-is-clojuremcp)
- [Where the project is headed](#where-the-project-is-headed)
- [Help and Community Resources](#help-and-community-resources)
- [📋 Installation](#-installation)
- [CLI Agents (recommended)](#cli-agents-recommended)
- [The Full Toolkit (legacy)](#the-full-toolkit-legacy)
- [🔧 Customization](#-customization)
- [CLI Options](#cli-options)
- [⚙️ Configuration](#-configuration)
- [📝 License](#-license)

## What is ClojureMCP?

ClojureMCP is an MCP server that connects a coding agent to a running
nREPL in your project. With it, the agent can do what Clojure
programmers do: explore in the REPL, check its assumptions against
the running program, and test changes as it makes them.

- **`clojure_eval`** - evaluates code over a persistent nREPL
  connection, repairing unbalanced delimiters before evaluating.
  Works with Clojure, ClojureScript (shadow-cljs, Figwheel),
  Babashka, Basilisp, and Scittle.
- **`list_nrepl_ports`** - discovers the nREPL servers running on
  your machine, so the agent can find and switch between REPLs.
- **`paren_repair`** - a safety valve for the rare delimiter tangle
  that's hard to fix by string editing.
- **`cljs_eval`**, **`cljs_build_status`**, **`cljs_project_state`**,
  **`cljs_list_ns`** - evaluate in, and inspect, a shadow-cljs build's
  live runtime (browser or Node).

That's the whole recommended setup: the `:repl` profile. Your agent
keeps its own file reading, editing, search and shell tools.

## Where the project is headed

ClojureMCP began in 2025 as a complete coding toolkit for Claude
Desktop, at a time when LLMs regularly mangled Clojure's parentheses.
So it shipped its own structure-aware editors (`clojure_edit`,
`clojure_edit_replace_sexp`), a collapsed `read_file`, file and shell
tools, sub-agents, prompts and resources.

That has changed. Current frontier models (recent Claude models in
particular) edit Clojure reliably with the built-in editors of Claude
Code and Codex, and they rarely get into delimiter trouble they can't
get out of. The part of ClojureMCP that still matters is the REPL.

So:

- **The `:repl` profile is the recommended way to use ClojureMCP.**
- **The editing tools and the rest of the full toolkit still work**
  and remain available. They're useful if you're building your own
  harness, or if you're driving a model that does struggle with
  Clojure edits. But they're in maintenance mode and on a long path
  to deprecation, unless new, effective LLM editing approaches are
  discovered that are best delivered through ClojureMCP.

## Help and Community Resources

* The [#ai-assisted-coding Channel on Clojurians Slack](https://clojurians.slack.com/archives/C068E9L5M2Q) is very active and where I spend a lot of time.
* The [ClojureMCP Wiki](https://github.com/bhauman/clojure-mcp/wiki) has info on various integrations and sandboxing.

## 📋 Installation

### Prerequisites

- [Clojure](https://clojure.org/guides/install_clojure)
- [Java](https://openjdk.org/) (JDK 17 or later)
- An nREPL in your project (see [doc/nrepl.md](doc/nrepl.md)), or let ClojureMCP start one with `:start-nrepl-cmd` or `:fallback-nrepl`
- Full toolkit only: [ripgrep](https://github.com/BurntSushi/ripgrep#installation) for better `grep` and `glob_files` performance

### Install ClojureMCP

Install ClojureMCP using the Clojure tools installer:

```bash
clojure -Ttools install-latest :lib io.github.bhauman/clojure-mcp :as mcp
```

This installs ClojureMCP globally, making `clojure -Tmcp start` available from any directory.

## CLI Agents (recommended)

CLI coding agents (Claude Code, Codex, Gemini CLI) already have
excellent file editing, search and shell tools. ClojureMCP adds the
one thing they're missing: a live REPL.

### Adding ClojureMCP with `:repl`

Run from your project directory:

```bash
# Claude Code
claude mcp add clojure-mcp -- clojure -Tmcp start :config-profile :repl

# OpenAI Codex
codex mcp add clojure-mcp -- clojure -Tmcp start :config-profile :repl

# Google Gemini CLI
gemini mcp add clojure-mcp clojure -Tmcp start :config-profile :repl
```

By default ClojureMCP connects to the nREPL port in your project's
`.nrepl-port` file. If you don't keep a REPL running, add
`:fallback-nrepl true` to have ClojureMCP start one for you, or
`:start-nrepl-cmd '["clojure" "-M:nrepl"]'` to start your own alias.

**Why an MCP tool instead of evaluating via the shell?** Agents
reach for a first-class REPL tool far more readily than for a shell
command, and the connection is persistent: state you build up in the
REPL stays there between evaluations. If you'd rather not run an MCP
server at all, [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light)
provides a shell-based nREPL client and a delimiter-repair hook instead.

### Working with Multiple REPLs

With `list_nrepl_ports`, the agent can discover both your Clojure and shadow-cljs REPLs simultaneously, including the port shadow-cljs writes to `.shadow-cljs/nrepl.port`. The tool identifies which REPLs are shadow-cljs instances, allowing the agent to evaluate on either REPL using `clojure_eval` with the appropriate `port` parameter.

The full toolkit also has shadow-cljs tools: `cljs_eval` evaluates ClojureScript in a build's live runtime (browser or Node) with no nested REPL, `cljs_build_status` shows a build's last compile result and warnings, `cljs_project_state` lists active builds and connected runtimes, and `cljs_list_ns` lists a build's namespaces. Their `port` defaults to `.shadow-cljs/nrepl.port` and their `build` to [`:cljs-build`](CONFIG.md#cljs-build) or the only active build.

### Shared daemon (one JVM for every project)

Each stdio server is its own JVM, and starting one takes 15 s or more. The shared daemon serves every project and every agent session on the machine from one JVM, and agents keep connecting over plain stdio through a small [babashka](https://babashka.org) shim. The shim needs `bb` and the `clojure` CLI on `PATH`.

Commit this to a project's `.mcp.json`, pinning a commit `<sha>` of this repository in both places:

```json
{"mcpServers": {"clojure-mcp": {"command": "bb", "args": [
  "-Sdeps", "{:deps {io.github.bhauman/clojure-mcp {:git/url \"https://github.com/bhauman/clojure-mcp\" :git/sha \"<sha>\" :deps/root \"shim\"}}}",
  "-m", "clojure-mcp.shim", "--sha", "<sha>"]}}}
```

- The shim finds the project from its working directory: the nearest ancestor holding `.git`, `deps.edn`, `bb.edn` or `shadow-cljs.edn`. Each project gets its own config (`.clojure-mcp/config.edn`), nREPL connection and scratch pad, so an agent in one project never evaluates in another's REPL.
- The shim answers the client's `initialize` at once and starts the daemon if none is running, so a cold start does not trip the client's connect timeout. Run the same command with `--warm` to start the daemon ahead of time.
- Each session's environment is forwarded, so `bash`, API-key lookups and `ENABLE_TOOLS`/`DISABLE_TOOLS` see the caller's environment, not the environment of whichever shell started the daemon.
- The sub-agent tools (`dispatch_agent`, `architect`, `code_critique`, `clojure_edit_agent`) are listed when the daemon starts with an API key in its environment, or with an `:agent-model-fallback` hook (a qualified symbol passed to `clojure-mcp.daemon/start`; see `clojure-mcp.daemon.server/start!`) that offers a model for that environment. The list is fixed then: a later session's own key is used for its calls, but does not add the tools.
- The daemon listens on `127.0.0.1` only and requires a token that only the shim reads. Its state and `daemon.log` live in `~/.cache/clojure-mcp/daemons/<sha>/`. It exits after 8 hours without requests. A different `<sha>` gets its own daemon.
- Projects share one tool list: a tool turned off in a project's config still appears, and calling it says it is disabled. Agents a project defines under `:agents`, and its `:tools-config` description overrides, do not appear in it.
- For a local checkout of this repository, use `--local-root <checkout>` instead of `--sha`, with `{:local/root "<checkout>/shim"}` in `-Sdeps`.

### Older CLI profiles

These editing-focused profiles are still shipped. They're mainly
useful with models that struggle to edit Clojure.

All three include the `:repl` tools (`clojure_eval`,
`list_nrepl_ports`, `paren_repair` and the `cljs_*` tools) plus `deps_list`, `deps_grep` and
`deps_read` for searching and reading source in dependency jars.

- **`:cli-assist`** - structural editors as a fallback for when the
  host's Edit tool fails to apply a change.
  Adds: `clojure_edit`, `clojure_edit_replace_sexp`.
- **`:cli-assist-agent`** - like `:cli-assist`, but the form-level
  fallback is an LLM sub-agent that applies loosely specified edits
  (requires an API key).
  Adds: `clojure_edit_agent`, `clojure_edit_replace_sexp`.
- **`:cli-assist-full`** - all Clojure reading and editing routed
  through ClojureMCP's tools instead of the host's.
  Adds: `read_file`, `clojure_edit`, `clojure_edit_replace_sexp`.

See [doc/full-toolkit.md](doc/full-toolkit.md#editing-profiles-for-cli-agents) for more.

### Check install by starting the server

From your project directory:

```bash
clojure -Tmcp start :config-profile :repl
```

You should see JSON-RPC output like this:

```json
{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
{"jsonrpc":"2.0","method":"notifications/resources/list_changed"}
{"jsonrpc":"2.0","method":"notifications/prompts/list_changed"}
```

## The Full Toolkit (legacy)

Without a `:config-profile`, ClojureMCP serves its original full
toolkit: structure-aware editors, a collapsed file reader, file and
shell tools, sub-agents, prompts and resources. It was built to turn
Claude Desktop into a Clojure coding assistant, and it's in
maintenance mode now (see
[Where the project is headed](#where-the-project-is-headed)).

If you're reaching for Claude Desktop to write Clojure, you'll be
better served by Claude Code, Codex or another programming harness
with the [`:repl` profile](#cli-agents-recommended).

Setup for Claude Desktop, the older editing profiles, API keys for
the agent tools, the full tool list and file-tool configuration are
all in **[doc/full-toolkit.md](doc/full-toolkit.md)**.

## 🔧 Customization

If you're building your own harness, ClojureMCP's tools, prompts and
resources can be assembled into a custom MCP server: pick the tools
you want, add your own as multimethods or simple maps, and expose your
own prompts and resources.

See [Creating Your Own Custom MCP Server](doc/custom-mcp-server.md) to
get started, and the [customization docs](doc/README.md) for the rest.

## CLI options

Values passed to `clojure -Tmcp start` are EDN values.

#### `:port`
**Optional** - The nREPL server port to connect to. When using `:start-nrepl-cmd` without `:port`, the port will be automatically discovered from the command output.

`:port 7888`

#### `:host`
**Optional** - The nREPL server host. Defaults to localhost if not specified.

`:host "localhost"` or `:host "0.0.0.0"`

#### `:not-cwd`
**Optional** - If true, don't use the current working directory as the project directory. Requires `:port` to be specified. The MCP server will introspect the nREPL connection to discover the project's working directory. Needed for clients that launch the MCP server outside your project directory, such as Claude Desktop.

`:not-cwd true`

#### `:start-nrepl-cmd`
**Optional** - A command to automatically start an nREPL server if one is not already running. Must be specified as a vector of strings. The MCP server will start this process and manage its lifecycle. 

When used without `:port`, the MCP server will automatically parse the port from the command's output. When used with `:port`, it will use that fixed port instead.

**Important**: The nREPL server is started in the current working directory, so launch `clojure-mcp` from your project directory (where your `deps.edn` or `project.clj` is located). CLI agents do this already. For clients that launch servers elsewhere, also pass `:project-dir`.

`:start-nrepl-cmd ["lein" "repl" ":headless"]` or `:start-nrepl-cmd ["clojure" "-M:nrepl"]`

#### `:fallback-nrepl`
**Optional** - When `true`, ClojureMCP will first try to attach to `:port`. If nothing is listening there (or `:port` is omitted entirely), it spawns a local nREPL on an **ephemeral** port and connects to that. The fallback never squats on your configured `:port`, so a later editor session can still claim it.

This is intended for users who don't always have an editor-managed nREPL running. Without this flag, ClojureMCP fails to start if it can't reach `:port`.

The default command is built from clojure-mcp's own nREPL dependency (so no version is hardcoded) and runs through `clojure -Sdeps ... -M -m nrepl.cmdline`. The user's `~/.clojure/deps.edn` is still merged in by the `clojure` CLI, so libraries you keep globally available (e.g. Criterium) remain on the spawned REPL's classpath.

The spawned process is cleaned up automatically when the MCP server shuts down.

`:fallback-nrepl true`

#### `:fallback-nrepl-cmd`
**Optional** - Overrides the default fallback command. Must be a vector of strings. Only used when `:fallback-nrepl` is `true`. Do not include an explicit port in the command; the launcher needs to parse the discovered port from the process output.

`:fallback-nrepl-cmd ["lein" "repl" ":headless"]`

#### `:fallback-nrepl-dir`
**Optional** - Working directory for the spawned fallback REPL. Defaults to `:project-dir` if set, otherwise to `$HOME`. Useful when you want the fallback REPL to pick up a project's `deps.edn` automatically.

`:fallback-nrepl-dir "/path/to/scratch"`

#### `:config-file`
**Optional** - Specify the location of a configuration file. Must be a path to an existing file.

`:config-file "/path/to/config.edn"`

#### `:project-dir`
**Optional** - Specify the working directory for your codebase. This overrides the automatic introspection of the project directory from the nREPL connection. Must be a path to an existing directory.

`:project-dir "/path/to/your/clojure/project"`

#### `:nrepl-env-type`
**Optional** - Specify the type of environment that we are connecting to over the nREPL connection. This overrides automatic detection. Valid options are:

* `:clj` for Clojure or ClojureScript
* `:bb` for [Babashka](https://babashka.org/) - Native, fast starting Clojure interpreter for scripting
* `:basilisp` for [Basilisp](https://basilisp.readthedocs.io/) - A Clojure-compatible Lisp dialect targeting Python 3.9+
* `:scittle` for [Scittle](https://github.com/babashka/scittle) - Execute ClojureScript directly from browser script tags

`:nrepl-env-type :bb`

#### `:shadow-cljs-repl-message`
**Optional** - Controls whether the shadow-cljs REPL mode status message is included in eval results (default: `true`). When connected to a shadow-cljs nREPL, a status message about CLJS mode is prepended to every eval result. Set to `false` to disable this message.

`:shadow-cljs-repl-message false`

#### `:config-profile`
**Optional** - Load a built-in configuration profile that adjusts tool availability and descriptions. Useful for tailoring ClojureMCP to specific use cases.

Available profiles:
* `:repl` - **Recommended.** REPL-only toolset for CLI coding agents (Claude Code, Codex, Gemini CLI): `clojure_eval`, `list_nrepl_ports`, `paren_repair` and the shadow-cljs tools (`cljs_eval`, `cljs_build_status`, `cljs_project_state`, `cljs_list_ns`), with no prompts or resources. The agent uses its own tools for all reading and editing. Uses allowlists, so tools added to ClojureMCP later stay off unless the profile lists them.
* `:cli-assist`, `:cli-assist-agent`, `:cli-assist-full` - *(legacy)* Editing-focused profiles; see [doc/full-toolkit.md](doc/full-toolkit.md#editing-profiles-for-cli-agents).

`:config-profile :repl`

#### `:enable-tools`
**Optional** - Allowlist of tool keywords. When provided, replaces any `:enable-tools` value from config. Only the listed tools will be available.

`:enable-tools [:clojure_eval :read_file]`

#### `:disable-tools`
**Optional** - Blocklist of tool keywords. When provided, replaces any `:disable-tools` value from config. The listed tools will be disabled.

`:disable-tools [:bash :dispatch_agent]`

#### `:add-tools`
**Optional** - Force-enable specific tools after config resolution. Removes tools from the disable list, and adds them to the enable list if one is active. This is useful for selectively re-enabling tools that a config profile disables.

`:add-tools [:my_custom_agent]`

#### `:remove-tools`
**Optional** - Force-disable specific tools after config resolution. Adds tools to the disable list, and removes them from the enable list if one is active. This is useful for selectively disabling tools without replacing the entire config.

`:remove-tools [:clojure_eval]`

#### Tool filtering application order

1. Config loaded (home + project + profile merge)
2. `:enable-tools`/`:disable-tools` from opts replace config values (if provided)
3. `:remove-tools` applied (force-disable)
4. `:add-tools` applied (force-enable — wins over `:remove-tools` on overlap)
5. `ENABLE_TOOLS`/`DISABLE_TOOLS` env vars still win over everything

See [Component Filtering](doc/component-filtering.md) for details on how enable/disable lists work in config files.

### Example Usage

```bash
# Recommended for CLI coding agents: REPL tools only
clojure -Tmcp start :config-profile :repl

# REPL only, connecting to a specific port
clojure -Tmcp start :config-profile :repl :port 7888

# REPL only, spawning an nREPL if none is running
clojure -Tmcp start :config-profile :repl :fallback-nrepl true

# REPL only, starting your project's nREPL alias (port is discovered)
clojure -Tmcp start :config-profile :repl :start-nrepl-cmd '["clojure" "-M:nrepl"]'

# Leiningen project
clojure -Tmcp start :config-profile :repl :start-nrepl-cmd '["lein" "repl" ":headless"]'

# Babashka nREPL
clojure -Tmcp start :config-profile :repl :port 1667 :nrepl-env-type :bb

# With custom host and project directory
clojure -Tmcp start :port 7888 :host '"0.0.0.0"' :project-dir '"/path/to/project"'

# Using a custom config file
clojure -Tmcp start :port 7888 :config-file '"/path/to/custom-config.edn"'

# REPL only, plus one of the legacy structural editors
clojure -Tmcp start :config-profile :repl :add-tools '[:clojure_edit]'

# Strictly REPL: drop paren_repair too
clojure -Tmcp start :config-profile :repl :remove-tools '[:paren_repair]'

# Full override — only these two tools
clojure -Tmcp start :enable-tools '[:clojure_eval :read_file]'
```

**Note**: String values need to be properly quoted for the shell, hence `'"value"'` syntax for strings.

## ⚙️ Configuration

ClojureMCP reads optional project configuration from a
`.clojure-mcp/config.edn` file in your project's root directory. The
`:repl` profile usually needs none. Most options configure the full
toolkit's file, shell and agent tools (see
[doc/full-toolkit.md](doc/full-toolkit.md#file-tool-configuration)).

### Configuration File Location

Create a `.clojure-mcp/config.edn` file in your project root:

```
your-project/
├── .clojure-mcp/
│   └── config.edn
├── src/
├── deps.edn
└── ...
```

### Configuration Options

All options are documented in [CONFIG.md](CONFIG.md).

## 📝 License

Eclipse Public License - v 2.0

Copyright (c) 2025 Bruce Hauman

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0

### License Summary

- ✅ **Use freely** for personal projects, internal business tools, and development
- ✅ **Modify and distribute** - improvements and forks are welcome
- ✅ **Commercial use** - businesses can use this commercially without restrictions
- ✅ **Flexible licensing** - can be combined with proprietary code
- 📤 **Share improvements** - source code must be made available when distributed
