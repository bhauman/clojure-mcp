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

### Setting up the project

#### Step 1: Setup a home for the Clojure MCP server

Set it up as git dep in a local `deps.edn` or global `.clojure/deps.edn` like:

```clojure
{:aliases 
  {:mcp 
    {:deps {org.slf4j/slf4j-nop {:mvn/version "2.0.16"}
            com.bhauman/clojure-mcp {:git/url "https://github.com/bhauman/clojure-mcp.git"
                                     :git/sha "FILL THIS WITH LATEST SHA"}}
     :exec-fn clojure-mcp.main/start-mcp-server
     :exec-args {:port 7888}}}}
```

> **Finding the latest SHA**: Visit [https://github.com/bhauman/clojure-mcp/commits/main](https://github.com/bhauman/clojure-mcp/commits/main) to get the latest commit SHA, or clone the repo and run `git log --oneline -1` to see the latest commit.

or from a local clone of `clojure-mcp`

```clojure
{:aliases 
  {:mcp 
    {:deps {org.slf4j/slf4j-nop {:mvn/version "2.0.16"}
            com.bhauman/clojure-mcp {:local/root "~/workspace/clojure-mcp"}}
     :exec-fn clojure-mcp.main/start-mcp-server
     :exec-args {:port 7888}}}}
```

> **Local clone path**: Replace `~/workspace/clojure-mcp` with the actual path where you cloned the repository (e.g., `~/dev/clojure-mcp`, `/Users/username/projects/clojure-mcp`, etc.)

> IMPORTANT NOTE: the mcp server can run in any directory and DOES NOT
> have to run from your project directory.  The mcp server looks to
> the nREPL connection for context.  The root directory of the project
> that is running the nREPL server becomes the root directory of all
> the mcp tool invocations. Currently the nREPL must run on the same
> machine as the MCP server as there is an assumption of a shared file
> system between the nREPL server and the MCP server.

> ANOTHER IMPORTANT NOTE: `clojure-mcp` should not run as part of your
> project and your project dependencies should not mingle with
> clojure-mcp. It should run separately, with its own set of deps. So
> if you include it in your projects `deps.edn` it should not use
> `:extra-deps` in its alias is should always use `:deps`

#### Step 2: Configure Your Target Project

In the Clojure project where you want AI assistance, add an nREPL connection.

```clojure
{:aliases {
  ;; nREPL server for AI to connect to
  :nrepl {:extra-paths ["test"]
          :extra-deps {nrepl/nrepl {:mvn/version "1.3.1"}}
          :jvm-opts ["-Djdk.attach.allowAttachSelf"]
          :main-opts ["-m" "nrepl.cmdline" "--port" "7888"]}}}
```

#### Step 3: Configure Claude Desktop

Edit your Claude Desktop configuration file:
- **Location**: `~/Library/Application Support/Claude/claude_desktop_config.json`

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/bin/sh",
            "args": [
                "-c",
                "cd ~/workspace/clojure-mcp && PATH=/opt/homebrew/bin:$PATH && clojure -X:mcp :port 7888"
            ]
        }
    }
}
```

**Replace these paths**:
- `~/workspace/clojure-mcp` → Your clojure-mcp location (same as Step 1)
- `/opt/homebrew/bin` → Your system's binary path:
  - **Homebrew (Intel Mac)**: `/usr/local/bin`
  - **Homebrew (Apple Silicon)**: `/opt/homebrew/bin`  
  - **Nix**: `/home/username/.nix-profile/bin` or `/nix/var/nix/profiles/default/bin`
  - **System default**: Often `/usr/bin:/usr/local/bin` works

### Optional: Setting up the project using Nix

#### Step 1: Configure Your Target Project

In the Clojure project where you want AI assistance, add an nREPL connection.

```clojure
{:aliases {
  ;; nREPL server for AI to connect to
  :nrepl {:extra-paths ["test"]
          :extra-deps {nrepl/nrepl {:mvn/version "1.3.1"}}
          :jvm-opts ["-Djdk.attach.allowAttachSelf"]
          :main-opts ["-m" "nrepl.cmdline" "--port" "7888"]}}}
```

#### Step 2: Configure Claude Desktop

> [!NOTE]
> When restarting Claude Desktop for the first time with these changes, a loading spinner is shown while Nix downloads and builds clojure-mcp.

Edit your Claude Desktop configuration file:
- **Location**: `~/Library/Application Support/Claude/claude_desktop_config.json`

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/bin/sh",
            "args": [
                "-c",
                "PATH=/run/current-system/sw/bin:$PATH && nix run github:bhauman/clojure-mcp"
            ]
        }
    }
}
```

**Replace these paths**:
- `/run/current-system/sw/bin` → Your `nix` binary location (try `which nix` to find it).

**Override defaults**:
- Check for available options with `nix run github:bhauman/clojure-mcp -- --help`
- Pass options like `nix run github:bhauman/clojure-mcp -- --port 7888`

### Test the Setup

1. **Start nREPL** in your target project:
   ```bash
   cd /path/to/your/project
   clojure -M:nrepl
   ```
   You should see: `nREPL server started on port 7888...`

2. **Start or Restart Claude Desktop** (required after config changes)

3. **Verify connection**: In Claude Desktop, click the `+` button in the chat area. You should see "Add from clojure-mcp" in the menu.

### Starting a new conversation

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

#### LLM API Keys

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
| `fs_grep` | Fast content search using regular expressions | Finding files containing specific patterns |
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

## 🎛️ Customization

The Clojure MCP server is designed for easy customization. The codebase separates the core MCP server API from the specific implementation:

- **`src/clojure_mcp/core.clj`** - Provides the reusable API for building MCP servers
- **`src/clojure_mcp/main.clj`** - Example implementation showing how to use the API

### Using main.clj as a Template

The `main.clj` file demonstrates the recommended patterns for creating your own customized MCP server:

#### 1. Define Your Resources (`my-resources` function)

```clojure
(defn my-resources [nrepl-client-map working-dir]
  (keep identity
    [(resources/create-file-resource
       "custom://project-summary"
       "PROJECT_SUMMARY.md"
       "Project summary for LLM context"
       "text/markdown"
       (str working-dir "/PROJECT_SUMMARY.md"))
     ;; Add more resources here
     ]))
```

Resources provide context documents to the AI assistant. Common resources include:
- Project documentation (README, PROJECT_SUMMARY)
- Code style guides
- Project structure information
- Configuration files

#### 2. Define Your Prompts (`my-prompts` function)

```clojure
(defn my-prompts [working-dir]
  [{:name "my_custom_prompt"
    :description "Custom prompt for specific workflows"
    :arguments []
    :prompt-fn (prompts/simple-content-prompt-fn
                "Custom Prompt"
                "Your custom prompt content here")}])
```

Prompts provide pre-configured instructions and workflows for the AI assistant.

#### 3. Select Your Tools (`my-tools` function)

```clojure
(defn my-tools [nrepl-client-atom]
  [;; Read-only tools
   (directory-tree-tool/directory-tree-tool nrepl-client-atom)
   (unified-read-file-tool/unified-read-file-tool nrepl-client-atom)
   
   ;; Evaluation tools
   (eval-tool/eval-code nrepl-client-atom)
   
   ;; Editing tools
   (combined-edit-tool/unified-form-edit-tool nrepl-client-atom)
   (file-write-tool/file-write-tool nrepl-client-atom)
   
   ;; Add your custom tools here
   ])
```

Tools are organized by category:
- **Read-only**: File exploration, project inspection
- **Evaluation**: Code execution and testing  
- **Editing**: File modification and code generation
- **Agents**: Advanced AI-powered tools
- **Experimental**: Cutting-edge features

#### 4. Wire Everything Together (`start-mcp-server` function)

```clojure
(defn start-mcp-server [nrepl-args]
  (let [nrepl-client-map (core/create-and-start-nrepl-connection nrepl-args)
        working-dir (config/get-nrepl-user-dir nrepl-client-map)
        mcp (core/mcp-server)]
    
    (reset! core/nrepl-client-atom (assoc nrepl-client-map ::mcp-server mcp))
    
    ;; Register your customizations
    (doseq [resource (my-resources nrepl-client-map working-dir)]
      (core/add-resource mcp resource))
    (doseq [tool (my-tools core/nrepl-client-atom)]
      (core/add-tool mcp tool))
    (doseq [prompt (my-prompts working-dir)]
      (core/add-prompt mcp prompt))))
```

### Creating Your Custom Server

1. **Copy `main.clj`** as a starting point for your custom server
2. **Modify the functions** to include only the tools/resources/prompts you need
3. **Add requires** for any additional tools you want to include
4. **Update your `deps.edn`** to point to your custom server function

Example `deps.edn` configuration:
```clojure
:mcp {:exec-fn my.custom.server/start-mcp-server
      :exec-args {:port 7888}}
```

### Adding New Tools

To add tools not included in the default set:

1. **Find the tool namespace** in `src/clojure_mcp/tools/`
2. **Add the require** to your namespace
3. **Add the tool function call** to your `my-tools` function

Example:
```clojure
(:require [clojure-mcp.tools.my-new-tool.tool :as my-new-tool])

;; In my-tools function:
(my-new-tool/my-new-tool nrepl-client-atom)
```

### Tool Categories and Selection

Choose tools based on your workflow needs:

- **For exploration only**: Include read-only and evaluation tools
- **For active development**: Add editing and file manipulation tools  
- **For AI-assisted workflows**: Include agent and experimental tools
- **For testing**: Include bash tool for running tests

This modular approach lets you create focused, efficient MCP servers tailored to specific development workflows.

## 🔧 Extending with Custom Tools

Want to create your own MCP tools?

**📖 [Custom Tools Development Guide](CUSTOM_TOOLS.md)** 

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

**Prerequisites for Emacs Integration:**
- `emacsclient` must be available in your system PATH
- Emacs server must be running (start with `M-x server-start` or add `(server-start)` to your init file)
- The integration allows the MCP server to communicate with your Emacs editor for enhanced development workflows

### Example Configuration

```edn
{:allowed-directories ["."
                       "src" 
                       "test" 
                       "resources"
                       "dev"
                       "/absolute/path/to/shared/code"
                       "../sibling-project"]
 :emacs-notify false}
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
 :emacs-notify false}
```

#### Multi-Project Setup
```edn
{:allowed-directories ["."
                       "../shared-utils"
                       "../common-config"
                       "/home/user/reference-code"]
 :emacs-notify false}
```

#### Restricted Mode (Extra Security)
```edn
{:allowed-directories ["src" 
                       "test"]
 :emacs-notify false}
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
