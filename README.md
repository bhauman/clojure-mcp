# Clojure MCP: REPL-Driven Development with AI Assistance

ClojureMCP is an MCP server Clojure!

## Table of Contents

- [What is ClojureMCP?](#what-is-clojuremcp)
- [How do I use it?](#how-do-i-use-it)
- [Main Features](#main-features)
- [Help and Community Resources](#help-and-community-resources)
- [📋 Installation](#-installation)
- [CLI Assistants](#cli-assistants)
- [Claude Desktop](#claude-desktop)
  - [Project Summary Management](#project-summary-management)
  - [Chat Session Summarize and Resume](#chat-session-summarize-and-resume)
- [LLM API Keys](#llm-api-keys)
- [🧰 Available Tools](#-available-tools)
- [🔧 Customization](#-customization)
- [CLI Options](#cli-options)
- [⚙️ Configuration](#-configuration)
- [📝 License](#-license)

## What is ClojureMCP?

ClojureMCP is an MCP server that connects an LLM client (like Claude
Code or Claude Desktop) to your Clojure project. It provides REPL
tools and Clojure-aware editing tools designed to handle Clojure's
parentheses and formatting reliably.

Depending on your LLM client, ClojureMCP can either:
- Provide a **complete set of Clojure aware code assistance tools** for desktop chat apps like Claude Desktop, or
- Fill in Clojure-specific gaps for **CLI assistants** that already have great file editing and shell tools (such as REPL integration + Clojure-aware edits).

## How do I use it?

1. Install ClojureMCP (`clojure -Ttools install-latest ...`).
2. Register it as an MCP server in your LLM client.

If you're using a CLI assistant, you'll usually prefer to keep the CLI's native file editing tools and use ClojureMCP mainly for REPL integration (and as an editing fallback).

If you're using a desktop chat app, you'll typically use the full ClojureMCP toolchain.

## Main Features

- **Clojure REPL Connection** - which repairs delimiters prior to evaluation
- **Clojure Aware editing** - Using parinfer, cljfmt, and clj-rewrite
- **Optimized set of tools for Clojure Development**

## Help and Community Resources

* The [#ai-assisted-coding Channel on Clojurians Slack](https://clojurians.slack.com/archives/C068E9L5M2Q) is very active and where I spend a lot of time.
* The [ClojureMCP Wiki](https://github.com/bhauman/clojure-mcp/wiki) has info on various integrations and sandboxing.

## 📋 Installation

### Prerequisites

- [Clojure](https://clojure.org/guides/install_clojure)
- [Java](https://openjdk.org/) (JDK 17 or later)
- **Optional but HIGHLY recommended**: [ripgrep](https://github.com/BurntSushi/ripgrep#installation) for better `grep` and `glob_files` performance

### Install ClojureMCP

Install ClojureMCP using the Clojure tools installer:

```bash
clojure -Ttools install-latest :lib io.github.bhauman/clojure-mcp :as mcp
```

This installs ClojureMCP globally, making `clojure -Tmcp start` available from any directory.

## CLI Assistants

CLI coding assistants (Claude Code, Codex, Gemini CLI) already have great inline-diff editing and shell tools.

**Start with [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light)** - it provides REPL integration and delimiter repair while preserving your CLI's native inline-diff display. This works well for most Clojure development.

**Consider adding ClojureMCP** with the `:cli-assist` profile if you want:
- **Structural editing fallback** - clojure-mcp-light can repair parens after an edit succeeds, but can't help when the find/replace match string doesn't match the code (happens <5% of the time, but can throw off the LLM). Structural editing targets forms by type and name, avoiding this problem.
- **First-class REPL tool** - LLMs tend to use MCP tools more readily than CLI commands, which may lead to more frequent REPL usage.

Adding ClojureMCP to clojure-mcp-light can provide an enhanced Clojure development experience for CLI assistants.

### Adding ClojureMCP with `:cli-assist`

```bash
# Claude Code
claude mcp add clojure-mcp -- clojure -Tmcp start :config-profile :cli-assist

# OpenAI Codex
codex mcp add clojure-mcp -- clojure -Tmcp start :config-profile :cli-assist

# Google Gemini CLI
gemini mcp add clojure-mcp clojure -Tmcp start :config-profile :cli-assist
```

### Check install by starting the server

From your project directory:

```bash
clojure -Tmcp start :config-profile :cli-assist
```

You should see JSON-RPC output like this:

```json
{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
{"jsonrpc":"2.0","method":"notifications/resources/list_changed"}
{"jsonrpc":"2.0","method":"notifications/prompts/list_changed"}
```

## Claude Desktop

Desktop chat apps (like Claude Desktop) start MCP servers outside your
project directory and do not provide built-in coding tools.  In this
environment, you’ll typically use the full ClojureMCP toolchain and
connect it to an nREPL running in your project.

ClojureMCP was initially developed to turn Claude Desktop into a
coding assistant similar to Claude Code with tools designed to work
effectively with the Clojure programming language.

### Start an nREPL in your project

Start an nREPL server from your project directory.
If you don't already have an nREPL alias or configuration, see [doc/nrepl.md](doc/nrepl.md).

### Configure Claude Desktop

Pick the shell executable that will most likely pick up your
environment config:

If you are using **Bash** find the explicit `bash` executable path:

```bash
$ which bash
/opt/homebrew/bin/bash
```

If you are using **Z Shell** find the explicit `zsh` executable path:

```bash
$ which zsh
/bin/zsh
```

Now we're going to use this explicit shell path in the `command`
parameter in the Claude Desktop configuration as seen below.

Create or edit `~/Library/Application\ Support/Claude/claude_desktop_config.json`:

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

The `:not-cwd true` flag tells ClojureMCP not to use the current working directory (which for Claude Desktop is not your project). Instead, it introspects the nREPL connection to discover the project's working directory.

This allows a simple working pattern of starting a REPL on 7888, then
starting Claude Desktop and allowing it to detect where you are working.

When you want to switch to a different project you would stop the
current REPL running on 7888 and start a nREPL server in the project
you want to work in on port 7888.

### Test the setup

1. **Start nREPL** in your target project:
   ```bash
   cd /path/to/your/project
   clojure -M:nrepl
   ```
   Look for: `nREPL server started on port 7888...`

2. **Restart Claude Desktop** (required after configuration changes)

3. **Verify Connection**: In Claude Desktop, click the `+` button in the chat area. You should see "Add from clojure-mcp" in the menu. It's important to note that it may take a few moments for this to show up.

4. If there was an error please see the [Troubleshooting Guide](doc/troubleshooting.md). If it connected, go see the [Starting a new conversation in Claude Desktop](#starting-a-new-conversation-in-claude-desktop) section.

### IMPORTANT: Turn Claude Desktop capabilities off

**Code Execution and file creation**: `off`

Code execution and file creation provides tools that compete with
ClojureMCP; it's best to turn them off.

Go to settings > Capabilities > Code Execution and file creation and toggle it off.

You may also want to turn **Artifacts** off as well.

### Other Clients besides Claude Desktop

See the [Wiki](https://github.com/bhauman/clojure-mcp/wiki) for
information on setting up other MCP clients.

### Starting a new conversation in Claude Desktop

Once everything is set up I'd suggest starting a new chat in Claude.

The first thing you are going to want to do is initialize context
about the Clojure project in the conversation attached to the nREPL.

In Claude Desktop click the `+` tools and optionally add
 * resource `PROJECT_SUMMARY.md`  - (have the LLM create this) see below
 * resource `Clojure Project Info` - which introspects the nREPL connected project
 * resource `LLM_CODE_STYLE.md` - Which is your personal coding style instructions (copy the one in this repo to the root of your project)
 * prompt `clojure_repl_system_prompt` - instructions on how to code - cribbed a bunch from Claude Code

Then start the chat.

I would start by stating a problem and then chatting with the LLM to
interactively design a solution. You can ask Claude to "present a solution for my review".

Iterate on that a bit then have it either:

A. code and validate the idea in the REPL.

> Don't underestimate LLMs abilities to use the REPL! Current LLMs are
> absolutely fantastic at using the Clojure REPL.

B. ask the LLM to make the changes to the source code and then have it validate the code in the REPL after file editing.

C. ask to run the tests.
D. ask to commit the changes.

> Make a branch and have the LLM commit often so that it doesn't ruin good work by going in a bad direction.

### Project Summary Management

This project includes a workflow for maintaining an LLM-friendly `PROJECT_SUMMARY.md` that helps assistants quickly understand the codebase structure.

#### How It Works

1. **Creating the Summary**: To generate or update the PROJECT_SUMMARY.md file, use the MCP prompt in the `+` > `clojure-mcp` menu `create-update-project-summary`. This prompt will:
   - Analyze the codebase structure
   - Document key files, dependencies, and available tools
   - Generate comprehensive documentation in a format optimized for LLM assistants

2. **Using the Summary**: When starting a new conversation with an assistant:
   - The "Project Summary" resource automatically loads PROJECT_SUMMARY.md
   - This gives the assistant immediate context about the project structure
   - The assistant can provide more accurate help without lengthy exploration

3. **Keeping It Updated**: At the end of a productive session where new features or components were added:
   - Invoke the `create-update-project-summary` prompt again
   - The system will update the PROJECT_SUMMARY.md with newly added functionality
   - This ensures the summary stays current with ongoing development

This workflow creates a virtuous cycle where each session builds on the accumulated knowledge of previous sessions, making the assistant increasingly effective as your project evolves.

### Chat Session Summarize and Resume

The Clojure MCP server provides a pair of prompts that enable
conversation continuity across chat sessions using the `scratch_pad`
tool. By default, data is stored **in memory only** for the current session.
To persist summaries across server restarts, you must enable scratch pad
persistence using the configuration options described in the scratch pad section.

#### How It Works

The system uses two complementary prompts:

1. **`chat-session-summarize`**: Creates a summary of the current conversation
   - Saves a detailed summary to the scratch pad
   - Captures what was done, what's being worked on, and what's next
   - Accepts an optional `chat_session_key` parameter (defaults to `"chat_session_summary"`)

2. **`chat-session-resume`**: Restores context from a previous conversation
   - Reads the PROJECT_SUMMARY.md file
   - Calls `clojure_inspect_project` for current project state
   - Retrieves the previous session summary from scratch pad
   - Provides a brief 8-line summary of where things left off
   - Accepts an optional `chat_session_key` parameter (defaults to `"chat_session_summary"`)

#### Usage Workflow

**Ending a Session:**
1. At the end of a productive conversation, invoke the `chat-session-summarize` prompt
2. The assistant will store a comprehensive summary in the scratch pad
3. This summary persists across sessions thanks to the scratch pad's global state

**Starting a New Session:**
1. When continuing work, invoke the `chat-session-resume` prompt
2. The assistant will load all relevant context and provide a brief summary
3. You can then continue where you left off with full context

#### Advanced Usage with Multiple Sessions

You can maintain multiple parallel conversation contexts by using custom keys:

```
# For feature development
chat-session-summarize with key "feature-auth-system"

# For bug fixing
chat-session-summarize with key "debug-memory-leak"

# Resume specific context
chat-session-resume with key "feature-auth-system"
```

This enables switching between different development contexts while maintaining the full state of each conversation thread.

#### Working with Multiple REPLs

With `list_nrepl_ports`, the agent can discover both your Clojure and shadow-cljs REPLs simultaneously. The tool identifies which REPLs are shadow-cljs instances, allowing the agent to evaluate on either REPL using `clojure_eval` with the appropriate `port` parameter.

## LLM API Keys

> This is NOT required to use the Clojure MCP server.

> IMPORTANT: if you have the following API keys set in your
> environment, then ClojureMCP will make calls to them when you use
> the `dispatch_agent`,`architect` and `code_critique` tools. These
> calls will incur API charges.

There are a few MCP tools provided that are agents unto themselves and they need API keys to function.

To use the agent tools, you'll need API keys from one or more of these providers:

- **`GEMINI_API_KEY`** - For Google Gemini models
  - Get your API key at: https://makersuite.google.com/app/apikey
  - Used by: `dispatch_agent`, `architect`, `code_critique`

- **`OPENAI_API_KEY`** - For GPT models
  - Get your API key at: https://platform.openai.com/api-keys
  - Used by: `dispatch_agent`, `architect`, `code_critique`

- **`ANTHROPIC_API_KEY`** - For Claude models
  - Get your API key at: https://console.anthropic.com/
  - Used by: `dispatch_agent`

#### Setting Environment Variables

**Option 1: Export in your shell**
```bash
export ANTHROPIC_API_KEY="your-anthropic-api-key-here"
export OPENAI_API_KEY="your-openai-api-key-here"
export GEMINI_API_KEY="your-gemini-api-key-here"
```

**Option 2: Add to your shell profile** (`.bashrc`, `.zshrc`, etc.)
```bash
# Add these lines to your shell profile
export ANTHROPIC_API_KEY="your-anthropic-api-key-here"
export OPENAI_API_KEY="your-openai-api-key-here"
export GEMINI_API_KEY="your-gemini-api-key-here"
```

#### Configuring LLM Keys for Claude Desktop

When setting up Claude Desktop, ensure it can access your environment variables by updating your config.

Personally I `source` them right in bash command:

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/bin/sh",
            "args": [
                "-c",
                "source ~/.api_credentials.sh && PATH=/your/bin/path:$PATH && clojure -Tmcp start :not-cwd true :port 7888"
            ]
        }
    }
}
```

> **Note**: The agent tools will work with any available API key. You don't need all three - just set up the ones you have access to. The tools will automatically select from available models. For now the ANTHROPIC API is limited to the dispatch_agent.

## 🧰 Available Tools

The default tools included in `main.clj` are organized by category to support different workflows:

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

### Key Tool Features

#### Smart File Reading (`read_file`)
- **Collapsed View**: Shows only function signatures for large Clojure files
- **Pattern Matching**: Use `name_pattern` to find functions by name, `content_pattern` to search content
- **defmethod Support**: Handles dispatch values like `"area :rectangle"` or vector dispatches
- **Multi-language**: Clojure files get smart features, other files show raw content

#### Structure-Aware Editing (`clojure_edit`)
- **Form-based Operations**: Target functions by type and identifier, not text matching
- **Multiple Operations**: Replace, insert_before, insert_after
- **Syntax Validation**: Built-in linting prevents unbalanced parentheses
- **defmethod Handling**: Works with qualified names and dispatch values

#### Code Evaluation (`clojure_eval`)
- **REPL Integration**: Executes in the connected nREPL session
- **Helper Functions**: Built-in namespace and symbol exploration tools
- **Multiple Expressions**: Evaluates and partitions multiple expressions

#### Shell Commands (`bash`)
- **Configurable Execution**: Can run over nREPL or locally based on config
- **Session Isolation**: When using nREPL mode, runs in separate session to prevent REPL interference
- **Output Truncation**: Consistent 8500 character limit with smart stderr/stdout allocation
- **Path Security**: Validates filesystem paths against allowed directories

#### Agent System (`dispatch_agent`)
- **Autonomous Search**: Handles complex, multi-step exploration tasks
- **Read-only Access**: Agents have read only tool access
- **Detailed Results**: Returns analysis and findings

#### Scratch Pad (`scratch_pad`)
- **Persistent Workspace**: Store structured data for planning and inter-tool communication
- **Memory-Only**: Data is stored in memory only and lost when session ends (default behavior)
- **Path-Based Operations**: Use `set_path`, `get_path`, `delete_path` for precise data manipulation
- **JSON Compatibility**: Store any JSON-compatible data (objects, arrays, strings, numbers, booleans)

## 🔧 Customization

ClojureMCP is designed to be highly customizable. During the alpha phase, creating your own custom MCP server is the primary way to configure the system for your specific needs.

You can customize:
- **Tools** - Choose which tools to include, create new ones with multimethods or simple maps
- **Prompts** - Add project-specific prompts for your workflows
- **Resources** - Expose your documentation, configuration, and project information
- **Tool Selection** - Create read-only servers, development servers, or specialized configurations

The customization approach is both easy and empowering - you're essentially building your own personalized AI development companion.

**📖 [Complete Customization Documentation](doc/README.md)**

For a quick start: **[Creating Your Own Custom MCP Server](doc/custom-mcp-server.md)** - This is where most users should begin.

## CLI options

Values passed to `clojure -Tmcp start` are EDN values.

#### `:port`
**Optional** - The nREPL server port to connect to. When using `:start-nrepl-cmd` without `:port`, the port will be automatically discovered from the command output.

`:port 7888`

#### `:host`
**Optional** - The nREPL server host. Defaults to localhost if not specified.

`:host "localhost"` or `:host "0.0.0.0"`

#### `:not-cwd`
**Optional** - If true, don't use the current working directory as the project directory. Requires `:port` to be specified. The MCP server will introspect the nREPL connection to discover the project's working directory.

This is essential for Claude Desktop and other clients that launch the MCP server outside your project directory. By connecting to an nREPL running in your project, ClojureMCP can determine the correct working directory automatically.

`:not-cwd true`

#### `:start-nrepl-cmd`
**Optional** - A command to automatically start an nREPL server if one is not already running. Must be specified as a vector of strings. The MCP server will start this process and manage its lifecycle. 

When used without `:port`, the MCP server will automatically parse the port from the command's output. When used with `:port`, it will use that fixed port instead.

**Important**: This option requires launching `clojure-mcp` from your project directory (where your `deps.edn` or `project.clj` is located). The nREPL server will be started in the current working directory. This is particularly useful for Claude Code and other command-line LLM clients where you want automatic nREPL startup without manual process management.

**Note for Claude Desktop users**: Claude Desktop does not start MCP servers from your project directory, so `:start-nrepl-cmd` will not work unless you also provide `:project-dir` as a command line argument pointing to your specific project. For example: `:project-dir '"/path/to/your/clojure/project"'`. This limitation does not affect Claude Code or other CLI-based tools that you run from your project directory.

`:start-nrepl-cmd ["lein" "repl" ":headless"]` or `:start-nrepl-cmd ["clojure" "-M:nrepl"]`

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

#### `:config-profile`
**Optional** - Load a built-in configuration profile that adjusts tool availability and descriptions. Useful for tailoring ClojureMCP to specific use cases.

Available profiles:
* `:cli-assist` - Minimal toolset for CLI coding assistants (Claude Code, Codex, Gemini CLI). Disables redundant tools and configures `clojure_edit` as a fallback for when native Edit fails.

`:config-profile :cli-assist`

### Example Usage

```bash
# Basic usage with just port
clojure -Tmcp start :port 7888

# With automatic nREPL server startup and port discovery
# Perfect for CLI assistants - run this from your project directory
clojure -Tmcp start :start-nrepl-cmd '["lein" "repl" ":headless"]'

# For deps.edn projects (from project directory)
clojure -Tmcp start :start-nrepl-cmd '["clojure" "-M:nrepl"]'

# Auto-start with explicit port (uses fixed port, no parsing)
clojure -Tmcp start :port 7888 :start-nrepl-cmd '["clojure" "-M:nrepl"]'

# For Claude Desktop: must provide project-dir since it doesn't run from your project
clojure -Tmcp start :start-nrepl-cmd '["lein" "repl" ":headless"]' :project-dir '"/path/to/your/clojure/project"'

# With custom host and project directory
clojure -Tmcp start :port 7888 :host '"0.0.0.0"' :project-dir '"/path/to/project"'

# Using a custom config file
clojure -Tmcp start :port 7888 :config-file '"/path/to/custom-config.edn"'

# Specifying Babashka environment
clojure -Tmcp start :port 7888 :nrepl-env-type :bb

# Using cli-assist profile for CLI coding assistants
clojure -Tmcp start :config-profile :cli-assist
```

**Note**: String values need to be properly quoted for the shell, hence `'"value"'` syntax for strings.

## ⚙️ Configuration

The Clojure MCP server supports minimal project-specific configuration
through a `.clojure-mcp/config.edn` file in your project's root
directory. This configuration provides security controls and
customization options for the MCP server.

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

Configuration is extensively documented [here](doc/CONFIG.md).

### Example Configuration

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

### Configuration Details

**Path Resolution**:
- Relative paths (like `"src"`, `"../other-project"`) are resolved relative to your project root
- Absolute paths (like `"/home/user/shared"`) are used as-is
- The project root directory is automatically included in allowed directories

**Security**:
- Tools validate all file operations against the allowed directories
- Attempts to access files outside allowed directories will fail with an error
- This prevents accidental access to sensitive system files
- the Bash tool doesn't respect these boundaries so be wary

**Default Behavior**:
- Without a config file, only the project directory and its subdirectories are accessible
- The nREPL working directory is automatically added to allowed directories

**Note**: Configuration is loaded when the MCP server starts. Restart the server (or the Chat Agent) after making configuration changes.

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
