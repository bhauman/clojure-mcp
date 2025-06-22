# Clojure MCP - REPL-Driven Development with AI Assistance

> **⚠️ Alpha Software - Work in Progress**
> 
> This project is in early development and rapidly evolving. While I've found it invaluable for working with Clojure projects and it has significantly improved my development workflow, expect breaking changes, rough edges, and incomplete documentation.
> 
> **🤝 Help Wanted!** If you find this useful, please consider contributing:
> - Report bugs and issues you encounter
> - Suggest improvements or new features
> - Submit pull requests for fixes or enhancements
> - Share your configuration patterns and workflows
> - Help improve documentation and examples
> 
> Your feedback and contributions will help make this tool better for the entire Clojure community!

A Model Context Protocol (MCP) server for Clojure that provides a
complete set of tools to aid in the development of Clojure projects.

## TLDR: what does this all mean for me?

With Clojure MCP alone you can turn an LLM into a powerful Clojure
REPL and coding assistant.

**LLMs excel in the Clojure REPL:** Current LLMs are unarguably
fantastic Clojure REPL assistants that perform evaluations quickly and
much more effectively than you can imagine. Ask anyone who has
experienced this and they will tell you that the LLMs are performing
much better in the Clojure REPL than they would have
imagined. Additionally, we must remember that the form and
maintainability of ephemeral code DOES NOT MATTER.

**Buttery Smooth Clojure Editing:** With current editing tools, LLMs
still struggle with the parenthesis. Clojure MCP has a different take
on editing that increases edit acceptance rates significantly. Clojure
MCP lints code coming in, fixes parenthesis if possible, uses
clj-rewrite to apply syntax aware patches, and then lints and formats
the final result. This is a powerful editing pipeline that vastly
outperforms when it comes to editing Clojure Code.

Together these two features along with a set of other Clojure aware
tools create a new and unique LLM development experience that you
probably should try at least once to understand how transformational
it is.

## The Good News

There is a story that Clojure developers may have come to believe. The
story that LLMs are overwhelmingly trained on more mainstream
languages and as a result those languages have the upper hand when it
comes to LLM assisted coding. I'm here to tell you that this is just
not true.

LLMs can definitely write Clojure. However, our the secret weapon is
the REPL and the fast focused feedback loop that it offers.

IMHO Clojure is an overwhemingly excellent langauge for LLM assisted
development.  All it needed was bit of a bridge... and this is what
I've tried to create with ClojureMCP.

## 🚀 Overview

This project implements an MCP server that connects AI models to a
Clojure nREPL, and Specialized Clojure editing tools enabling a unique
Clojure develop experience.

Clojure MCP provides a superset of the tools that Claude Code uses,
so you can use it to work on Clojure **without any other tools**.  I
highly recommend using it with Claude Desktop to start.  It's
more attractive and there are **no api charges!**. Claude Desktop, also let's you
have quick access to **your own prompts** and other resources provided
by the clojure-mcp server. Having a stack of your own prompts
available in a UI menu is very convenient.

> If you use the built in Agent tools you will accumulate API charges.

## Main Features

- **Clojure REPL Connection** - which lints the eval and auto-balances parens
- **Clojure Aware editing** - Using clj-kondo, parinfer, cljfmt, and clj-rewrite
- **Optimized set of tools for Clojure Development** superset of Claude Code
- **Emacs edit highlighting** - alpha

### Why REPL-Driven Development with AI?

For Clojurists an LLM assisted REPL is the killer application.

LLMs can:
* **Iterate** on code in the REPL and when finished present the findings before adding them to your code
* **Validate** and probe your code for errors
* **Debug** your code in the REPL
* and much more

Additionally, in some LLM clients (including Claude Desktop), you can
control which tools are available to the model at any given moment so
you can easily remove the ability to edit files and restrict the model
to the REPL tool and force the use of the REPL.

## 🧠 Model Compatibility

These tools are designed to work with the latest LLM models. For the best experience with sexp editing and Clojure-specific tooling, we recommend:

- **Anthropic Claude 3.7** and **Claude 4 (sonnet or opus)** (especially **Claude 4** for best results)
- **Gemini 2.5**
- **OpenAI o4-mini** or **o3**

I highly recommend **Claude 4** if you want to see long autonomous agentic action chains ...

The pattern-based structural editing tools require high model performance, so using one of these recommended models will significantly improve your experience.

## Cohesive Clojure Toolbox

### Why These Tools Work as a Complete System

The Clojure MCP tools are intentionally designed as a **cohesive "action space"** for Clojure development, rather than a collection of independent utilities. This design approach offers several key advantages:

#### Enhanced Clojure Integration
- **Smart file editing** with automatic parenthesis balancing, linting, and formatting
- **Structure-aware operations** that understand Clojure syntax and semantics  
- **REPL-integrated development** with stateful namespace management

#### Stateful File Tracking
The tools maintain state about file read/write operations to ensure safety:
- Tracks when files were last read vs. modified externally
- Prevents editing conflicts by validating file state before modifications
- Enables multiple sequential edits after a single read operation
- Uses canonical path resolution for reliable file identification

#### Optimized Tool Interactions
When tools work together as a system, they can:
- Share context and state for more intelligent behavior
- Provide consistent interfaces and error handling
- Optimize the overall development workflow

### Using with Claude Code and Other Code Assistants

While you *can* use these tools alongside Claude Code and other code assistants with their own tooling, we recommend **trying the Clojure MCP tools independently first** to experience their full capabilities. Here's why:

**Potential Conflicts:**
- Both systems track file read/write state independently, which can cause confusion
- Overlapping tool functionality may lead to inconsistent behavior
- Mixed toolsets can dilute the optimized workflow experience

**Getting the Full Benefits:**
- Experience the curated Clojure development workflow as intended
- Understand how the tools complement each other
- Appreciate the Clojure-specific enhancements and safety features
- Develop familiarity with the integrated approach before mixing systems

Once you're comfortable with the Clojure MCP toolset, you can make informed decisions about whether to use it exclusively or integrate it with other code assistants and development tools based on your specific workflow needs.



## 📋 Installation

### Prerequisites

- [Clojure](https://clojure.org/guides/install_clojure) (1.11 or later)
- [Java](https://openjdk.org/) (JDK 11 or later)
- [Claude Desktop](https://claude.ai/download) (for the best experience)

# Setting up ClojureMCP

Setting up ClojureMCP can be challenging as it is currently in alpha and not optimized for quick installation. This guide will walk you through the process step by step.

## Installation Overview

1. **Configure nREPL**: Set up and verify an nREPL server on port `7888` in your project
2. **Install ClojureMCP**: Add `clojure-mcp` to your `~/.clojure/deps.edn`
3. **Configure MCP Client**: Set up `clojure-mcp` as an MCP server in Claude Desktop or other MCP clients

> **Note**: This setup verifies that all components work together. You can customize specific configuration details (like port numbers) after confirming the basic setup works.

## Step 1: Configure Your Target Project's nREPL Connection

In the Clojure project where you want AI assistance, you'll need to ensure you can start an nREPL server on port `7888`.

### For deps.edn Projects

Add an `:nrepl` alias to your project's `deps.edn`:

```clojure
{
  ;; ... your project dependencies ...
  :aliases {
    ;; nREPL server for AI to connect to
    ;; Include all paths you want available for development
    :nrepl {:extra-paths ["test"] 
            :extra-deps {nrepl/nrepl {:mvn/version "1.3.1"}}
            :jvm-opts ["-Djdk.attach.allowAttachSelf"]
            :main-opts ["-m" "nrepl.cmdline" "--port" "7888"]}}}
```

**Verify** the configuration:

```bash
$ clojure -M:nrepl
```

You should see the nREPL server start on port `7888`.

### For Leiningen Projects

Start an nREPL server with:

```bash
$ lein repl :headless :port 7888
```

## Step 2: Install the Clojure MCP Server

Add `clojure-mcp` as an alias in your `~/.clojure/deps.edn`:

```clojure
{:aliases 
  {:mcp 
    {:deps {org.slf4j/slf4j-nop {:mvn/version "2.0.16"} ;; Required for stdio server
            com.bhauman/clojure-mcp {:git/url "https://github.com/bhauman/clojure-mcp.git"
                                     :git/tag "v0.1.5-alpha"
                                     :git/sha "5a0c9e2"}}
     :exec-fn clojure-mcp.main/start-mcp-server
     :exec-args {:port 7888}}}}
```

> **Finding the Latest Version**: Visit [https://github.com/bhauman/clojure-mcp/commits/main](https://github.com/bhauman/clojure-mcp/commits/main) for the latest commit SHA, or clone the repo and run `git log --oneline -1`.

### Verify the Installation

⚠️ **Important**: You must have an nREPL server running on port `7888` before starting `clojure-mcp`.

1. **First**, start your nREPL server in your project directory:
   ```bash
   $ clojure -M:nrepl
   # or for Leiningen:
   $ lein repl :headless :port 7888
   ```

2. **Then**, in a new terminal, start `clojure-mcp`:
   ```bash
   $ clojure -X:mcp :port 7888
   ```

You should see JSON-RPC output like this:

```json
{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
{"jsonrpc":"2.0","method":"notifications/resources/list_changed"}
{"jsonrpc":"2.0","method":"notifications/prompts/list_changed"}
```

### Troubleshooting

**Connection Refused Error**:
```
Execution error (ConnectException) at sun.nio.ch.Net/connect0 (Net.java:-2).
Connection refused
```
This means `clojure-mcp` couldn't connect to your nREPL server. Ensure:
- The nREPL server is running
- The port numbers match (default: 7888)

**Extraneous Output**: 
If you see output other than JSON-RPC messages, it's likely due to `clojure-mcp` being included in a larger environment. Ensure `clojure-mcp` runs with its own isolated dependencies.

### Important Notes

- **Location Independence**: The MCP server can run from any directory—it doesn't need to be in your project directory. It uses the nREPL connection for context.
- **Shared Filesystem**: Currently, the nREPL and MCP servers must run on the same machine as they assume a shared filesystem.
- **Dependency Isolation**: Don't include `clojure-mcp` in your project's dependencies. It should run separately with its own deps. Always use `:deps` (not `:extra-deps`) in its alias.

## Step 3: Configure Claude Desktop

This is often the most challenging part—ensuring the application's launch environment has the correct PATH and environment variables.

Create or edit `~/Library/Application\ Support/Claude/claude_desktop_config.json`:

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/bin/sh",
            "args": [
                "-c",
                "export PATH=/opt/homebrew/bin:$PATH; exec clojure -X:mcp :port 7888"
            ]
        }
    }
}
```

### Common PATH Locations

- **Homebrew (Apple Silicon)**: `/opt/homebrew/bin`
- **Homebrew (Intel Mac)**: `/usr/local/bin`
- **Nix**: `/home/username/.nix-profile/bin` or `/nix/var/nix/profiles/default/bin`
- **System Default**: `/usr/bin:/usr/local/bin`

### Advanced Configuration Example

If you need to source environment variables (like API keys):

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/bin/sh",
            "args": [
                "-c",
                "source ~/.my-llm-api-keys.sh && PATH=/Users/username/.nix-profile/bin:$PATH && clojure -X:mcp :port 7888"
            ]
        }
    }
}
```

## Step 4: Test the Complete Setup

1. **Start nREPL** in your target project:
   ```bash
   cd /path/to/your/project
   clojure -M:nrepl
   ```
   Look for: `nREPL server started on port 7888...`

2. **Restart Claude Desktop** (required after configuration changes)

3. **Verify Connection**: In Claude Desktop, click the `+` button in the chat area. You should see "Add from clojure-mcp" in the menu.

## Troubleshooting Tips

If Claude Desktop can't run the `clojure` command:

1. **Test your command manually**: Run the exact command from your config in a terminal
2. **Check your PATH**: Ensure `which clojure` works in a fresh terminal
3. **Enable logging**: Check Claude Desktop logs for error messages
4. **Simplify first**: Start with a basic configuration, then add complexity

If you continue to have issues, consider consulting with AI assistants (Claude, ChatGPT, Gemini) about the specific PATH configuration for your system setup.

### Other Clients besides Claude Desktop

See the [Wiki](https://github.com/bhauman/clojure-mcp/wiki) for
information on setting up other MCP clients.

## Starting a new conversation

Once everything is set up I'd suggest starting a new chat in Claude.

The first thing you are going to want to do is initialize context
about the Clojure project in the conversation attached to the nREPL.

In Claude Desktop click the `+` tools and optionally add
 * resource `PROJECT_SUMMARY.md`  - (have the LLM create this) see below
 * resource `Clojure Project Info` - which introspects the nREPL connected project
 * resource `LLM_CODE_STYLE.md` - Which is your personal coding style instructions (copy the one in this repo)
 * prompt `clojure_repl_system_prompt` - instructions on how to code - cribbed a bunch from Clod Code

Then start the chat.

I would start by stating a problem and then chatting with the LLM to
interactively design a solution. You can ask Claude to "propose" a
solution to a problem.

Iterate on that a bit then have it either:

A. code and validate the idea in the REPL.

> Don't underestimate LLMs abilities to use the REPL! Current LLMs are
> absolutely fantastic at using the Clojure REPL. 

B. ask the LLM to make the changes to the source code and then have it validate the code in the REPL after file editing.

C. ask to run the tests.
D. ask to commit the changes.

> Make a branch and have the LLM commit often so that it doesn't ruin good work by going in a bad direction.

## Project Summary Management

This project includes a workflow for maintaining an LLM-friendly `PROJECT_SUMMARY.md` that helps assistants quickly understand the codebase structure.

### How It Works

1. **Creating the Summary**: To generate or update the PROJECT_SUMMARY.md file, use the MCP prompt in the `+` > `clojure-mcp` menu `create-project-summary`. This prompt will:
   - Analyze the codebase structure
   - Document key files, dependencies, and available tools
   - Generate comprehensive documentation in a format optimized for LLM assistants

2. **Using the Summary**: When starting a new conversation with an assistant:
   - The "Project Summary" resource automatically loads PROJECT_SUMMARY.md
   - This gives the assistant immediate context about the project structure
   - The assistant can provide more accurate help without lengthy exploration

3. **Keeping It Updated**: At the end of a productive session where new features or components were added:
   - Invoke the `create-project-summary` prompt again
   - The system will update the PROJECT_SUMMARY.md with newly added functionality
   - This ensures the summary stays current with ongoing development

This workflow creates a virtuous cycle where each session builds on the accumulated knowledge of previous sessions, making the assistant increasingly effective as your project evolves.

## Chat Session Summarize and Resume

The Clojure MCP server provides a pair of prompts that enable
conversation continuity across chat sessions using the `scratch_pad`
tool. This will be stored **in memory**. Things stored in the `scratch_pad`
are not persisted to disk (yet).

### How It Works

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

### Usage Workflow

**Ending a Session:**
1. At the end of a productive conversation, invoke the `chat-session-summarize` prompt
2. The assistant will store a comprehensive summary in the scratch pad
3. This summary persists across sessions thanks to the scratch pad's global state

**Starting a New Session:**
1. When continuing work, invoke the `chat-session-resume` prompt
2. The assistant will load all relevant context and provide a brief summary
3. You can then continue where you left off with full context

### Advanced Usage with Multiple Sessions

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

### Benefits

- **Seamless Continuity**: Pick up exactly where you left off
- **Context Preservation**: Important details aren't lost between sessions
- **Multiple Contexts**: Work on different features/bugs in parallel
- **Reduced Repetition**: No need to re-explain what you're working on

The chat summarization feature complements the PROJECT_SUMMARY.md by capturing conversation-specific context and decisions that haven't yet been formalized into project documentation.

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

#### Configuring Claude Desktop

When setting up Claude Desktop, ensure it can access your environment variables by updating your config.

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/bin/sh",
            "args": [
                "-c",
                "cd /path/to/your/mcp-server/home && PATH=/your/bin/path:$PATH && clojure -X:mcp"
            ],
            "env": {
                "ANTHROPIC_API_KEY": "$ANTHROPIC_API_KEY",
                "OPENAI_API_KEY": "$OPENAI_API_KEY", 
                "GEMINI_API_KEY": "$GEMINI_API_KEY"
            }
        }
    }
}
```

Personally I `source` them right in bash command:

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/bin/sh",
            "args": [
                "-c",
                "source ~/.api_credentials.sh && cd /path/to/your/mcp-server/home && PATH=/your/bin/path:$PATH && clojure -X:mcp"
            ]
        }
    }
}
```

> **Note**: The agent tools will work with any available API key. You don't need all three - just set up the ones you have access to. The tools will automatically select from available models. For now the ANTHROPIC API is limited to the displatch_agent.


## Learning Curve

> This tool has a learning curve. You may in practice have to remind
> the LLM to develop in the REPL.  You may also have to remind the LLM
> to use the `clojure_edit` family of tools which have linters build
> in to prevent unbalanced parens and the like.

## 🧰 Available Tools

The default tools included in `main.clj` are organized by category to support different workflows:

### Read-Only Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `LS` | Returns a recursive tree view of files and directories | Exploring project structure |
| `read_file` | Smart file reader with pattern-based exploration for Clojure files | Reading files with collapsed view, pattern matching |
| `grep` | Fast content search using regular expressions | Finding files containing specific patterns |
| `glob_files` | Pattern-based file finding | Finding files by name patterns like `*.clj` |
| `think` | Log thoughts for complex reasoning and brainstorming | Planning approaches, organizing thoughts |

### Code Evaluation

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `clojure_eval` | Evaluates Clojure code in the current namespace | Testing expressions like `(+ 1 2)` |
| `bash` | Execute shell commands on the host system | Running tests, git commands, file operations |

### File Editing Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `clojure_edit` | Structure-aware editing of Clojure forms | Replacing/inserting functions, handling defmethod |
| `clojure_edit_replace_sexp` | Modify expressions within functions | Changing specific s-expressions |
| `file_edit` | Edit files by replacing text strings | Simple text replacements |
| `file_write` | Write complete files with safety checks | Creating new files, overwriting with validation |

### Agent Tools (Require API Keys)

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `dispatch_agent` | Launch agents with read-only tools for complex searches | Multi-step file exploration and analysis |
| `architect` | Technical planning and implementation guidance | System design, architecture decisions |

### Experimental Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
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

#### Agent System (`dispatch_agent`)
- **Autonomous Search**: Handles complex, multi-step exploration tasks
- **Read-only Access**: Agents have read only tool access
- **Detailed Results**: Returns analysis and findings



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

#### `allowed-directories`
Controls which directories the MCP tools can access for security. Paths can be relative (resolved from project root) or absolute.

#### `emacs-notify` 
Boolean flag to enable Emacs integration notifications.

Emacs notify is only a toy for now... it switches focuses on the file
being edited and highlights changes as they are happening.  There are
probably much better ways to handle this with auto-revert and existing
emacs libraries.

**Prerequisites for Emacs Integration:**
- `emacsclient` must be available in your system PATH
- Emacs server must be running (start with `M-x server-start` or add `(server-start)` to your init file)
- The integration allows the MCP server to communicate with your Emacs editor for enhanced development workflows

#### `cljfmt`
Boolean flag to enable/disable cljfmt formatting in editing pipelines (default: `true`). When disabled, file edits preserve the original formatting without applying cljfmt.

**Available values:**
- `true` (default) - Applies cljfmt formatting to all edited files
- `false` - Disables formatting, preserving exact whitespace and formatting

**When to use each setting:**
- `true` - Best for maintaining consistent code style across your project
- `false` - Useful when working with files that have specific formatting requirements or when you want to preserve manual formatting

#### `write-file-guard`
Controls the file timestamp tracking behavior (default: `:full-read`). This setting determines when file editing is allowed based on read operations.

**Available values:**
- `:full-read` (default) - Only full reads (`collapsed: false`) update timestamps. This is the safest option, ensuring the AI sees complete file content before editing.
- `:partial-read` - Both full and collapsed reads update timestamps. Allows editing after collapsed reads, providing more convenience with slightly less safety.
- `false` - Disables timestamp checking entirely. Files can be edited without any read requirement. Use with caution!

**When to use each setting:**
- `:full-read` - Best for team environments or when working with files that may be modified externally
- `:partial-read` - Good for solo development when you want faster workflows but still want protection against external modifications
- `false` - Only for rapid prototyping or when you're certain no external modifications will occur

The timestamp tracking system prevents accidental overwrites when files are modified by external processes (other developers, editors, git operations, etc.).

### Example Configuration

```edn
{:allowed-directories ["."
                       "src" 
                       "test" 
                       "resources"
                       "dev"
                       "/absolute/path/to/shared/code"
                       "../sibling-project"]
 :emacs-notify false
 :write-file-guard :full-read
 :cljfmt true}
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

### Common Configuration Patterns

#### Development Setup
```edn
{:allowed-directories ["." 
                       "src" 
                       "test" 
                       "dev" 
                       "resources"
                       "docs"]
 :emacs-notify false
 :write-file-guard :full-read
 :cljfmt true}
```

#### Multi-Project Setup
```edn
{:allowed-directories ["."
                       "../shared-utils"
                       "../common-config"
                       "/home/user/reference-code"]
 :emacs-notify false
 :write-file-guard :partial-read
 :cljfmt true}
```

#### Restricted Mode (Extra Security)
```edn
{:allowed-directories ["src" 
                       "test"]
 :emacs-notify false
 :write-file-guard :full-read
 :cljfmt false}  ; Preserve original formatting
```

**Note**: Configuration is loaded when the MCP server starts. Restart the server after making configuration changes.

## 📜 Development Practices

### Recommended Workflow

1. **Express the problem** - Clearly state what you want to solve
2. **Develop in the REPL** - Work through solutions incrementally
3. **Validate step-by-step** - Test each expression before moving on
4. **Save to files** - When the solution is working, save it properly
5. **Reload and verify** - Make sure the saved code works

### Best Practices

- **Small steps** - Prefer many small, valid steps over a few large steps
- **Human guidance** - Provide feedback to keep development on track
- **Test early** - Validate ideas directly in the REPL before committing to them

## 🔧 Project Maintenance

```bash
# Run tests
clojure -X:test

# Run specific test
clojure -X:test :dirs '["test"]' :include '"repl_tools_test"'

# Run linter
clojure -M:lint
```

## 📚 Philosophy

The core philosophy of this project is that:

1. **Tiny steps with rich feedback** lead to better quality code
2. **REPL-driven development** provides the highest quality feedback loop
3. **Keeping humans in the loop** ensures discernment and maintainable code

## 📝 License

GNU Affero General Public License v3.0

Copyright (c) 2025 Bruce Hauman

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.

### License Summary

- ✅ **Use freely** for personal projects, internal business tools, and development
- ✅ **Modify and distribute** - improvements and forks are welcome  
- ✅ **Commercial use** - businesses can use this internally without restrictions
- ⚠️ **Network copyleft** - if you offer this as a service to others, you must open source your entire service stack
- 📤 **Share improvements** - modifications must be shared under the same license

This license ensures the project remains open source while preventing commercial exploitation without contribution back to the community.
