# Clojure-MCP Configuration

If you want to tweak the operation of clojure-mcp but do not want to customize your own server,
you can now control most settings via a configuration file.

The default location for the configuration file is `.clojure-mcp/config.edn`. You can override this path
via the `:config-file` CLI arg (see README “CLI options”).

## Core Configuration

These basic settings affect how clojure-mcp interacts with your local system.

### `:allowed-directories`
Controls which directories the MCP tools can access for security. Paths can be relative (resolved from project root) or absolute.

### `:cljfmt`
Boolean flag to enable/disable cljfmt formatting in editing pipelines (default: `true`). When disabled, file edits preserve the original formatting without applying cljfmt.

**Available values:**
- `true` (default) - Applies cljfmt formatting to all edited files
- `false` - Disables formatting, preserving exact whitespace and formatting

**When to use each setting:**
- `true` - Best for maintaining consistent code style across your project
- `false` - Useful when working with files that have specific formatting requirements or when you want to preserve manual formatting

### `:write-file-guard`
Controls the file timestamp tracking behavior (default: `:partial-read`). This setting determines when file editing is allowed based on read operations.

**Available values:**
- `:partial-read` (default) - Both full and collapsed reads update timestamps. Allows editing after collapsed reads, providing more convenience with slightly less safety.
- `:full-read` - Only full reads (`collapsed: false`) update timestamps. This is the safest option, ensuring the AI sees complete file content before editing.
- `false` - Disables timestamp checking entirely. Files can be edited without any read requirement. Use with caution!

**When to use each setting:**
- `:partial-read` - Good for solo development when you want faster workflows but still want protection against external modifications
- `:full-read` - Best for team environments or when working with files that may be modified externally
- `false` - Only for rapid prototyping or when you're certain no external modifications will occur

The timestamp tracking system prevents accidental overwrites when files are modified by external processes (other developers, editors, git operations, etc.).

### `:emacs-notify`
Boolean flag to enable Emacs integration notifications.

Emacs notify is only a toy for now... it switches focus to the file
being edited and highlights changes as they happen. There are
probably better ways to handle this with auto-revert and existing
Emacs libraries.

**Prerequisites for Emacs Integration:**
- `emacsclient` must be available in your system PATH
- Emacs server must be running (start with `M-x server-start` or add `(server-start)` to your init file)
- The integration allows the MCP server to communicate with your Emacs editor for enhanced development workflows

### `:scratch-pad-load`
Boolean flag to automatically load the scratch pad on startup (default: `false`).

**Available values:**
- `false` (default) - Scratch pad is saved to disk but not loaded on startup
- `true` - Loads existing data on startup

**When to use each setting:**
- `false` - Best for temporary planning and session-only data
- `true` - When you want data to persist across sessions and server restarts

### `:scratch-pad-file`
Filename for scratch pad persistence (default: `"scratch_pad.edn"`).

**Configuration:**
- Specifies the filename within `.clojure-mcp/` directory

### `:bash-over-nrepl`
Boolean flag to control bash command execution mode (default: `true`). This setting determines whether bash commands are executed over the nREPL connection or locally on the MCP server.

**Available values:**
- `true` (default) - Execute bash commands over nREPL connection with isolated session
- `false` - Execute bash commands locally in the Clojure MCP server process

**When to use each setting:**
- `true` - Best for most development scenarios, as it allows you to sandbox only the nREPL server process
- `false` - Useful when the nREPL server is not a Clojure process (e.g., ClojureScript/CLJS, Babashka, Scittle)

**Technical details:**
- When `true`, bash commands run in a separate nREPL session
- Both modes apply consistent output truncation (8500 chars total, split between stdout/stderr)
- Local execution may be faster for simple commands but requires the MCP server to have necessary tools installed

### `:dispatch-agent-context`
Primes the dispatch agent with details about your code to help it find answers more quickly and accurately.

**Available values:**
- `true` (default) - Adds `PROJECT_SUMMARY.md` (if available) and `./.clojure-mcp/code_index.txt` into context
- Specifies a vector of specific files sent to `dispatch_agent`

NOTE: May consume more API tokens or even exceed the context window of the LLM

### Intermediate-Level Customization

Additional options allow you to fine-tune, augment, and even override default behavior.

Much of the behavior of clojure-mcp is exposed as components. These include resources, prompts, agents, tools, and models.

### Resources

Configured under the `:resources` key.

Resources include files and other content you want to use with clojure-mcp.
Resources provide read-only content like documentation, configuration files, or project information. This same approach works whether you're using ClojureMCP or creating standalone resources.

[Configuring Resources](doc/configuring-resources.md)

### Prompts

Configured under the `:prompts` key.

You can add your own prompts to clojure-mcp, then call them as-needed by name.
Prompts generate conversation contexts to help AI assistants understand specific tasks or workflows. This same approach works whether you're using ClojureMCP or creating standalone prompts.

[Creating Prompts](doc/creating-prompts.md)

### Models

Configured under the `:models` key.

You may want to use different models than provided by default.
Configure custom LLM models with your own API keys, endpoints, and parameters. Support for OpenAI, Anthropic, Google Gemini, and more through the LangChain4j integration.

[Model Configuration](doc/model-configuration.md)

### Agents

Configured under the `:agents` key.

See Agent configuration guidance in the README’s “Agent Tools” section; dedicated docs forthcoming.

### Tools Configuration

Configured under the `:tools-config` key.


### Component Filtering

For each server you have defined, you have granular control over the tools, prompts, and resources exposed by using filters.
Learn how to control which tools, prompts, and resources are exposed by your MCP server using enable/disable lists. Perfect for creating focused, secure, or specialized MCP servers with only the components you need.

[Component Filtering Configuration](doc/component-filtering.md)

### Advanced Customization

See the Custom MCP Server guide: `doc/custom-mcp-server.md`
