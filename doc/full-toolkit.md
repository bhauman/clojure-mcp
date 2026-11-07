# The Full Toolkit (legacy)

> **Maintenance mode.** Everything here still works, but it's no
> longer where the project is going. The recommended setup is the
> `:repl` profile with a CLI coding agent; see
> [Where the project is headed](../README.md#where-the-project-is-headed).

Without a `:config-profile`, ClojureMCP serves its full toolkit:
structure-aware editing, a collapsed file reader, file and shell
tools, sub-agents, prompts and resources. It was built to turn Claude
Desktop, which has no coding tools of its own, into a Clojure coding
assistant. It's also a ready-made set of parts for anyone building
their own agent harness for Clojure (see
[Creating Your Own Custom MCP Server](custom-mcp-server.md)).

- [Claude Desktop](#claude-desktop)
- [Editing profiles for CLI agents](#editing-profiles-for-cli-agents)
- [LLM API Keys](#llm-api-keys)
- [Available Tools](#available-tools)
- [File tool configuration](#file-tool-configuration)

## Claude Desktop

> **For coding, use a coding harness instead.** If you're reaching
> for Claude Desktop to write Clojure, you'll be better served by
> Claude Code, Codex or another programming harness, with ClojureMCP
> added via the [`:repl` profile](../README.md#cli-agents-recommended). Those
> harnesses come with strong file editing, search and shell tools
> built in. The setup below is kept for anyone who still wants to
> work from a desktop chat app.

Desktop chat apps start MCP servers outside your project directory,
so ClojureMCP connects to an nREPL you start in your project (see
[nrepl.md](nrepl.md)) and discovers the project from it.

### Configure Claude Desktop

Use the full path to your login shell (`which bash` or `which zsh`) as
the `command`, so the server picks up your environment. Create or edit
`~/Library/Application\ Support/Claude/claude_desktop_config.json`:

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/opt/homebrew/bin/bash",
            "args": [
                "-c",
                "clojure -Tmcp start :not-cwd true :port 7888"
            ]
        }
    }
}
```

The `:not-cwd true` flag tells ClojureMCP not to use the current working directory (which for Claude Desktop is not your project). Instead, it introspects the nREPL connection to discover the project's working directory. To switch projects, start your nREPL on port 7888 in the other project.

### Test the setup

1. **Start nREPL** in your target project:
   ```bash
   cd /path/to/your/project
   clojure -M:nrepl
   ```
   Look for: `nREPL server started on port 7888...`

2. **Restart Claude Desktop** (required after configuration changes)

3. **Verify Connection**: In Claude Desktop, click the `+` button in the chat area. You should see "Add from clojure-mcp" in the menu. It's important to note that it may take a few moments for this to show up.

4. If there was an error please see the [Troubleshooting Guide](troubleshooting.md).

### IMPORTANT: Turn Claude Desktop capabilities off

**Code Execution and file creation**: `off`

Code execution and file creation provides tools that compete with
ClojureMCP; it's best to turn them off.

Go to settings > Capabilities > Code Execution and file creation and toggle it off.

You may also want to turn **Artifacts** off as well.

### Other Clients besides Claude Desktop

See the [Wiki](https://github.com/bhauman/clojure-mcp/wiki) for
information on setting up other MCP clients.

## Editing profiles for CLI agents

These profiles predate `:repl`. They're mainly useful with models that
struggle to edit Clojure.

* `:cli-assist` - REPL plus `clojure_edit` and `clojure_edit_replace_sexp`, described as fallbacks for when the host's Edit tool fails to apply a change.
* `:cli-assist-agent` - Like `:cli-assist`, but the form-level fallback is the `clojure_edit_agent` sub-agent (requires an API key).
* `:cli-assist-full` - Promotes ClojureMCP's `read_file`, `clojure_edit`, `clojure_edit_replace_sexp` and `paren_repair` to **first-class** tools, and instructs the agent to route **all** Clojure reads and edits through them. This avoids the host editor's "file modified since read" conflict. For a hard guarantee, you can also add Claude Code `permissions.deny` rules like `"Edit(/**/*.clj)"` so the native editor can't touch Clojure files.

```bash
claude mcp add clojure-mcp -- clojure -Tmcp start :config-profile :cli-assist
```

## LLM API Keys

Not required, and not used by the `:repl` profile. The full toolkit's
agent tools (`dispatch_agent`, `architect`, `code_critique`,
`clojure_edit_agent`) call an LLM API using whichever of
`ANTHROPIC_API_KEY`, `OPENAI_API_KEY` or `GEMINI_API_KEY` is set in the
server's environment. Those calls incur API charges. Other providers
can be configured under `:models` in `.clojure-mcp/config.edn` (see
[CONFIG.md](../CONFIG.md)).

## Available Tools

The full toolkit (what you get with no `:config-profile`) is organized by category. The `:repl` profile uses only `clojure_eval`, `list_nrepl_ports`, `paren_repair` and the four `cljs_*` tools.

### Read-Only Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `LS` | Returns a recursive tree view of files and directories | Exploring project structure |
| `read_file` | Smart file reader with pattern-based exploration for Clojure files | Reading files with collapsed view, pattern matching |
| `grep` | Fast content search using regular expressions | Finding files containing specific patterns |
| `glob_files` | Pattern-based file finding | Finding files by name patterns like `*.clj` |

### Code Evaluation

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `clojure_eval` | Evaluates Clojure code in the current namespace; supports optional `port` parameter for multi-REPL workflows | Testing expressions, connecting to different REPLs |
| `list_nrepl_ports` | Discovers running nREPL servers on the machine | Finding available REPLs to connect to |
| `cljs_eval` | Evaluates ClojureScript in a shadow-cljs build's live runtime (browser or Node) via the one-shot `cljs-eval` API | Inspecting app state, driving a running cljs app |
| `cljs_build_status` | Last compile result of a shadow-cljs build: status, warnings, error report | Seeing a cljs compile error |
| `cljs_project_state` | Active shadow-cljs builds and their connected runtimes | Finding a `build` or `runtime_id` for `cljs_eval` |
| `cljs_list_ns` | Namespaces in a shadow-cljs build's compiler, filterable by regex | Finding the real name of a cljs namespace |
| `bash` | Execute shell commands on the host system | Running tests, git commands, file operations |

### File Editing Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `clojure_edit` | Structure-aware editing of Clojure forms | Replacing/inserting functions, handling defmethod |
| `clojure_edit_replace_sexp` | Modify expressions within functions | Changing specific s-expressions |
| `file_edit` | Edit files by replacing text strings | parinfer repair after edit if needed |
| `file_write` | Write complete files with safety checks | Creating new files, overwriting with validation |

### Agent Tools (Require API Keys)

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `dispatch_agent` | Launch agents with read-only tools for complex searches | Multi-step file exploration and analysis |
| `architect` | Technical planning and implementation guidance | System design, architecture decisions |

### Experimental Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `scratch_pad` | Persistent workspace for structured data storage | Task tracking, planning, inter-tool communication with optional file persistence (disabled by default) |
| `code_critique` | Interactive code review and improvement suggestions | Iterative code quality improvement |

## File tool configuration

These `.clojure-mcp/config.edn` options govern the full toolkit's file,
shell and formatting behavior. All options are documented in
[CONFIG.md](../CONFIG.md).

```edn
{:allowed-directories ["."
                       "src"
                       "test"
                       "resources"
                       "dev"
                       "/absolute/path/to/shared/code"
                       "../sibling-project"]
 :write-file-guard :partial-read
 :cljfmt false
 :bash-over-nrepl false}
```

### Details

**Path Resolution**:
- Relative paths (like `"src"`, `"../other-project"`) are resolved relative to your project root
- Absolute paths (like `"/home/user/shared"`) are used as-is
- The project root directory is automatically included in allowed directories

**Security** (full toolkit file tools):
- Tools validate all file operations against the allowed directories
- Attempts to access files outside allowed directories will fail with an error
- This prevents accidental access to sensitive system files
- the Bash tool doesn't respect these boundaries so be wary

**Default Behavior**:
- Without a config file, only the project directory and its subdirectories are accessible
- The nREPL working directory is automatically added to allowed directories

**Note**: Configuration is loaded when the MCP server starts. Restart the server (or the Chat Agent) after making configuration changes.
