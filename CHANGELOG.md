# Changelog

## [Unreleased]

## [0.2.0] - 2026-01-02

This release focuses on developer experience improvements with new tools for nREPL discovery, enhanced configuration flexibility through config profiles and environment variables, and a structural editing agent for Clojure code.

### Major Changes

#### New Tools
- **`list_nrepl_ports` tool**: Discover all running nREPL servers on your machine with environment type detection (clj, bb, shadow, etc.) and project directory information
- **`clojure_edit_agent`**: New structural editing agent for Clojure code - use as a fallback when the Edit tool has trouble with delimiter matching

#### Configuration Enhancements
- **Config profiles**: New `:config-profile` CLI option for resource-based configuration overlays - easily switch between different tool configurations
- **Environment variable support**: `ENABLE_TOOLS` and `DISABLE_TOOLS` environment variables for controlling which tools are loaded

### Added
- **Environment/namespace reporting**: Eval tool output now includes environment and namespace information
- **Optional nREPL in prompt-cli**: nREPL connection is now optional for the prompt CLI

### Changed
- **Improved shadow-cljs detection**: Better detection in `list_nrepl_ports` with helpful messages about listing builds
- **Simplified nREPL output format**: Cleaner, more readable output from `list_nrepl_ports`

### Removed
- **`directory_tree` tool**: Removed in favor of other exploration methods
- **Unused tools**: Removed `unified_clojure_edit` tool and sexp/match utilities

## [0.1.13] - 2025-12-21

This release introduces lazy nREPL initialization, allowing ClojureMCP to start without an immediate REPL connection. It also includes logging improvements, SDK updates, and significant code cleanup.

### Major Changes

#### Lazy nREPL Initialization
- **Start without REPL connection**: ClojureMCP can now start without connecting to an nREPL server immediately. REPL initialization happens lazily on first `eval-code` call.
- **New entry point `clojure-mcp.main/start`**: Automatically sets `:project-dir` to current working directory. Use `:not-cwd true` to disable this behavior.
- **Per-port state tracking**: Each port now tracks its own `env-type`, `initialized?`, and `project-dir` state.
- **`.nrepl-port` file fallback**: The eval tool, bash tool, and inspect-project tool can discover the nREPL port from `.nrepl-port` file in the project directory when no port is configured.

#### Logging Migration
- **Migrated to Timbre**: Replaced `clojure.tools.logging` with `taoensso.timbre` for pure Clojure logging with better configuration options.
- **Centralized logging configuration**: New `clojure-mcp.logging` namespace with namespace filtering and customizable options.
- **Logging disabled by default**: Enable with `:enable-logging? true` in config.

### Added
- **`clojure-mcp.main/start` entry point**: Simplified startup for CLI tools running from project directory
- **Per-port lazy initialization**: `ensure-port-initialized!`, `with-port`, `with-port-initialized` functions
- **Port state accessors**: `get-port-env-type`, `set-port-env-type!`, `port-initialized?`, `get-port-project-dir`
- **`.nrepl-port` file reading**: `read-nrepl-port-file` function for automatic port discovery

### Changed
- **MCP SDK updated to 0.15.0**: Compatibility updates for latest SDK
- **UTF-8 encoding standardized**: File I/O now uses UTF-8 encoding across all platforms
- **Edamame for delimiter checking**: Replaced clj-kondo with edamame for more permissive parsing
- **deps.edn simplified**: Removed hardcoded `:port 7888` defaults, updated aliases to use new `start` function

### Removed
- **Unused tools**: Removed think tool, clojure-edit-agent, edit location tracking
- **Unused prompts**: Cleaned up unused prompt definitions
- **Unused functions**: Removed dead code from form_edit/core, form_edit/pipeline, paren-utils
- **other_tools directory**: Removed unused tools and associated tests
- **Emacs notification support**: Removed unused notification code
- **Comment forms**: Cleaned up development comment blocks

### Fixed
- **clj-kondo warnings**: Fixed unused imports, redundant let bindings, docstring placement
- **Error semantics**: Reverted error semantics change, made edamame more permissive

### Internal
- **Dialect functions moved**: `detect-nrepl-env-type`, `initialize-environment`, `load-repl-helpers` moved from `dialects.clj` to `nrepl.clj` to avoid circular dependencies
- **Simplified `create-additional-connection`**: Now uses lazy initialization pattern

## [0.1.12] - 2025-11-06

This release changes the project license to EPL 2.0, adds an experimental prompt CLI, and includes several configuration improvements and dependency updates.

### Major Changes

#### License Change
- **Changed license from AGPL 3.0 to Eclipse Public License 2.0** - The project now uses EPL 2.0, providing more flexibility for commercial use and integration with proprietary code while still requiring sharing of modifications

### Added
- **Experimental Prompt CLI** (`clojure -M:prompt-cli`) for command-line AI interaction with session persistence and resume functionality - See [documentation](doc/prompt-cli.md)
- **Prompt to save custom user prompts to config** (#117)
- **Babashka script detection**: Improved detection of Babashka scripts with regex pattern matching for shebangs
- **dry_run parameter** for file editing tools (#121, #122) - Allows preview of edits before applying, this is mainly for integration in to ECA https://github.com/editor-code-assistant/eca

### Changed
- **bash-over-nrepl default**: Changed default to `false` Bash commands now run locally by default instead of over nREPL.
- **LangChain4j update**: Updated to latest version
- **Model updates**: Updated model configurations and definitions

### Fixed
- **Test suite**: Fixed failing tests after model.clj changes
- **Form edit pipeline**: Simplified error messages
- **Port parsing**: Improved patterns to prevent FlowStorm false positives (#113)
- **Documentation**: Fixed inaccurate mentions and spelling issues

### Removed
- **clojure_edit_replace_comment_block**: Removed outdated tool references

## [0.1.11-alpha] - 2025-10-04 Error Handling Changes in ClojureMCP Tool Responses

Recent changes to Claude Desktop and Claude Code prompted me to
reconsider how ClojureMCP tools use the error flag in their responses.

Claude Desktop and Claude Code are now limiting the view of results
when a tool responds with the error flag set to true.

Before this commit, the error flag was incorrectly triggered for
errors arising from normal tool operation (such as invalid parameters
or expected failure conditions). This approach made tool results less
accessible when a tool was used incorrectly, even though the tool
itself was functioning properly.

Going forward, the error flag will only be set to true for runtime
Exceptions that indicate actual tool malfunctions. This change ensures
tool results remain legible and expandable when users make mistakes in
tool usage, while still clearly signaling genuine system errors.

### Fixed
- Prevent validation errors from sending MCP tool result error flag - validation/processing errors now appear in results without signaling protocol-level errors
- Fix truncation message showing file size in bytes instead of line count (#107)

## [0.1.10-alpha] - 2025-09-19

### Simplified nREPL Auto-Start 🚀

**Launching an nREPL server is now incredibly simple!** Just provide `:start-nrepl-cmd` and the MCP server handles everything else - perfect for Claude Code and other CLI-based AI tools.

#### Quick Start Examples

**From your project directory**, just run:

```bash
# For Leiningen projects
clojure -X:mcp :start-nrepl-cmd '["lein" "repl" ":headless"]'

# For deps.edn projects  
clojure -X:mcp :start-nrepl-cmd '["clojure" "-M:nrepl"]'

# For Babashka scripts
clojure -X:mcp :start-nrepl-cmd '["bb" "nrepl-server"]'
```

That's it! The MCP server will:
- Start your nREPL server automatically
- Discover the port from the output
- Connect and manage the process lifecycle
- Clean up when you're done

#### Perfect for Claude Code

This feature is especially valuable for **Claude Code** users who want to start coding immediately without managing separate terminal windows:

1. Open Claude Code in your Clojure project directory
2. The nREPL starts automatically when Claude connects
3. Start coding with full REPL support!

You can also configure this in `.clojure-mcp/config.edn`:
```edn
{:start-nrepl-cmd ["clojure" "-M:nrepl"]}
```

#### Note for Claude Desktop Users

**Important**: Claude Desktop does not start MCP servers from your project directory, so `:start-nrepl-cmd` will not work unless you also provide `:project-dir` pointing to your specific project:

```bash
# For Claude Desktop, you must specify the project directory:
clojure -X:mcp :start-nrepl-cmd '["lein" "repl" ":headless"]' :project-dir '"/path/to/your/clojure/project"'
```

This limitation does not affect Claude Code or other CLI-based tools that you run directly from your project directory.

#### Using a Fixed Port

If you prefer a specific port, just add it:
```bash
clojure -X:mcp :start-nrepl-cmd '["lein" "repl" ":headless"]' :port 7888
```

### Breaking Changes
- **Removed `:parse-nrepl-port`**: This confusing option has been eliminated. The MCP server now intelligently determines whether to parse the port based on whether `:port` is provided. Simple!

### Migration Note
If you were using `:parse-nrepl-port`, just remove it:
- Old: `:start-nrepl-cmd ["..."] :parse-nrepl-port true` 
- New: `:start-nrepl-cmd ["..."]`

## [0.1.9-alpha] - 2025-9-16

### Major Refactoring: Configuration-Based Agent Tools

* You can now define agents, prompts, and resources in the clojure-mcp config.edn file
* You can now have a user based ~/.clojure-mcp/config.edn file that will be merged into your project local config file.
* For Claude Code and other CLI based llm client users, clojure-mcp can now start your nrepl for you and pass off the dynamic port number to clojure-mcp.
* Let me repeat, **you can create sub-agents** in the clojure-mcp configuration [Configuring Agents](doc/configuring-agents.md). This coupled with being able to define LLM models allows a tremendous amount of flexibility in your tooling.

All this and much much more!

### Added
- **Automatic nREPL Server Startup**: New `:start-nrepl-cmd` configuration option to automatically start nREPL servers (#86)
  - Automatically starts nREPL when specified in CLI args or config file
  - Supports common nREPL commands like `lein repl :headless`, `clj -M:nrepl`, etc.
  - Port discovery from command output with `:parse-nrepl-port` option
  - Process management with graceful shutdown and timeout handling
  - Vector format required for commands (e.g., `["lein" "repl" ":headless"]`)
- **Home Directory Config Support**: Config files can now be loaded from `~/.config/clojure-mcp/config.edn` (#99)
  - Provides user-level default configuration across all projects
  - Project-level `.clojure-mcp/config.edn` takes precedence over home directory config
  - Supports merging of configurations with proper precedence
- **Configuration Validation**: Malli based configuration validation to help with configuration errors
- **Agent Tool Builder System**: Dynamic agent creation from configurations
  - Configuration-based agent definitions via `:agents` in `.clojure-mcp/config.edn`
  - Tool-specific configurations merge with default agents via `:tools-config`
  - Supports custom model selection per agent
  - See [Configuring Agents](doc/configuring-agents.md) and [Tools Configuration](doc/tools-configuration.md) guides
- **Resource Configuration System**: New ability to control which files are exposed as resources
  - `:enable-resources` and `:disable-resources` for selective resource exposure
  - Control which documentation, project files, and prompts are available to AI assistants
  - Filter resources by name to reduce context and focus on relevant materials
  - See [Configuring Resources](doc/configuring-resources.md) guide
- **Prompt Configuration System**: Fine-grained control over AI prompts
  - `:enable-prompts` and `:disable-prompts` for prompt filtering
  - Selectively enable only the prompts needed for your workflow
  - See [Configuring Prompts](doc/configuring-prompts.md) and [Component Filtering](doc/component-filtering.md) guides
- **REPL Helpers Improvements**: Enhanced REPL interaction utilities (#98)
- **Agent Context Management**: Better isolation for file operations and conversation context
  - Agents now properly reset context between conversations
  - Improved file operation tracking

### Changed
- **Configuration Documentation**: Reorganized and cleaned up configuration documentation in README (#99)
  - Improved clarity and organization of configuration options
  - Better examples and explanations for common use cases
- **MCP SDK Dependency**: Updated to version 0.12.1
- **Agent Architecture**: Refactored all agents to use generalized agent library
  - Dispatch agent system message moved to resource file
  - Centralized tool construction via `tools.clj`
  - Eliminated circular dependencies
- **SQL File Support**: Mitigated errors when reading SQL files (#91)

### Fixed
- Agent context management now properly handles reset on every chat
- Test suite updated for refactored agent system

### Documentation
- Added namespace editing example to `clojure_edit` tool description
- Updated PROJECT_SUMMARY.md to reflect agent tools refactoring
- Initial draft of new configuration documentation (#97)
- Various documentation improvements and noise reduction

### Internal
- Removed 600+ lines of redundant hardcoded agent implementations
- Refactored `tools.clj` to eliminate circular dependencies
- Consolidated agent functionality into generalized agent library

### Contributors

Special thanks to all the contributors who made this release possible:

- **Hugo Duncan** (@hugoduncan) - For implementing the automatic nREPL server startup feature with port discovery (#86)
- **Jonathon McKitrick** (@jmckitrick) - For configuration improvements, home directory config support, and documentation (#97, #99)
- **Kenny Williams** (@kennyjwilli) - For numerous REPL helpers improvements (#98)
- **Mark Stuart** (@markaddleman) - For fixing SQL file reading support (#91)

## [0.1.8-alpha] - 2025-08-13

### What's New

This release brings major configuration enhancements and improved
Clojure code editing.

In terms of Clojure editing, these improvements are significant. There
used to be some edit thrashing when editing functions with comments
that directly preceed them. This is now fixed. When the LLM wants to
replace a function along with its preceeding comments, clojure-mcp does
the right thing.

The sexp replacement has been revamped yet again. Sexpr replacement
will now succeed most of the time while being preceeding comment aware
as well.

The other big step forward is the addition of custom model
configuration in the `config.edn`. This is a direct result of
`langchain4j` improving its model support. Now you can define custom
model configurations to be used by the agent tools.

Other `.clojure-mcp/config.edn` configuration improvements allow you to 
* `:enable-tools` `:disable-tools`
* `:enable-prompts` `:disable-prompts`
* `:enable-resources` `:disable-resources`
* add tool specific configuration in `:tools-config`

With all these changes, I'm going to be removing the `-alpha` from the next release tag.

** Key Highlights:**
- **Custom LLM Models** - Define your own model configurations in `.clojure-mcp/config.edn` with environment variable support for API keys
- **Scittle Support** - Connect directly to Scittle nREPL without configuration - fun stuff!
- **Component Filtering** - Choose exactly which tools, prompts, and resources your server exposes
- **Improved Code Editing** - Much better handling of preceeding comments and sexp editing
- **Tool Configuration** - Configure individual tools with custom settings including model selection

**Example Model Configuration:**
```edn
:models {;; use the OpenAI client to connect to other models
         :openai/glm {:model-name "z-ai/glm-4.5"
                      :api-key [:env "OPENROUTER_API_KEY"]
                      :base-url [:env "OPENROUTER_BASE_URL"]
                      :temperature 1}
         :anthropic/my-claude {:model-name "claude-3-5-sonnet-20241022"
                               :temperature 0.7}}

:tools-config {:dispatch_agent {:model :openai/my-fast}}
```

Currently these defined models are mainly used for the built-in agents, but if clojure-mcp had a cli that you could send prompts to...

**Documentation:**

These docs are incomplete but should get you started...

- [Component Filtering Guide](https://github.com/bhauman/clojure-mcp/blob/main/doc/component-filtering.md) - Control which components are exposed
- [Model Configuration Guide](https://github.com/bhauman/clojure-mcp/blob/main/doc/model-configuration.md)
- [Tools Configuration Guide](https://github.com/bhauman/clojure-mcp/blob/main/doc/tools-configuration.md)
- [Default Models](https://github.com/bhauman/clojure-mcp/blob/main/src/clojure_mcp/agent/langchain/model.clj)

**Coming Soon:**
- Editing agent for cleaner context management
- Expanded tool configuration options
- Custom resources, prompts, tools, and agents definable in config

#### Custom Model Configuration System
Complete support for user-defined LLM model configurations via `.clojure-mcp/config.edn`:
- **Named model definitions**: Define custom models under the `:models` key with provider-specific settings
- Environment API key support with `[:env "VAR_NAME"]` syntax
- Support for OpenAI, Anthropic, Google Gemini including setting the `:base-url` for the OpenAi client
- Built-in default models remain available when custom ones aren't defined

#### Tool-Specific Configuration System
New `:tools-config` key for configuring individual tools:
- **Per-tool settings**: Configure tools like `dispatch_agent`, `architect`, and `code_critique` with custom models
- **Example**: `:tools-config {:dispatch_agent {:model :openai/o3}}`

#### Component Filtering System
Fine-grained control over which components are enabled:
- **Tool filtering**: `:enable-tools` and `:disable-tools` configuration options
- **Prompt filtering**: `:enable-prompts` and `:disable-prompts` for controlling AI prompts
- **Resource filtering**: `:enable-resources` and `:disable-resources` for managing exposed files

#### S-Expression Editing Improvements (#77)
Major revamp of how s-expression editing works:
- **Better pattern matching**: More reliable matching of Clojure forms
- **Private function support**: `clojure_edit` now matches private `def-` and `defn-` forms flexibly
- **Comment-aware editing**: Improved handling of comments in top-level forms

#### Multi-dialect
- **Scittle support**: Initial support for Scittle (browser Clojure) files
- **Basilisp extension**: `.lpy` files now recognized as Clojure for editing

### Added
- **Claude 4.1 and GPT-5 models**: Support for latest AI models
- **5 additional OpenAI models**: Expanded model selection with simplified names
- **Code index documentation**: Initial documentation for code indexing feature (#76)
- **Bash tool enhancements**:
  - Configurable timeout with `timeout_ms` parameter
  - Additional configuration options for execution control

### Changed
- **Write-file-guard default**: Changed from `:full-read` to `:partial-read` for better usability
- **LangChain4j upgrade**: Updated to version 1.2.0 for improved model support
- **Dispatch agent context**: Added project info to dispatch agent context (#78, fixes #71)
- **Tool call prefixing**: Added note about correctly prefixing tool calls to system prompt

## [0.1.7-alpha] - 2025-07-22

Note the major changes listed in `0.1.7-alpha-pre` below which are part of this release.

Moving out of pre-release with several code fixes. A new configuration option allows the dispatch_agent to load files as context. The `:dispatch-agent-context` setting accepts either:
- `true` - automatically loads `PROJECT_SUMMARY.md` (if available) and `./.clojure-mcp/code_index.txt` (the new default output location for `clojure-mcp.code-indexer`)
- A vector of file paths - loads the specified files into the dispatch_agent context

This enables dispatch_agent to have better project awareness through pre-loaded context files.

### Fixed
- **Race condition in nREPL session creation** - Fixed critical race condition in `clojure-mcp.nrepl/new-session` that could cause session initialization failures
- **Working directory handling** - Shell commands now properly use the working directory when executing

### Added
- **:mcp-client-hint configuration** - New config option for providing hints to MCP clients about tool capabilities, used in scratch_pad tool schema - currently this allows me to experiment with using the more specific scratch_pad schema on claude desktop...
- **:dispatch-agent-context configuration** - New config option for providing context to dispatch agents 

### Changed
- **Updated to latest Clojure version** - Project now uses the most recent Clojure release
- **Code simplification** - Various internal simplifications to improve maintainability

### Documentation
- **Dual Shadow mode documentation** - Added documentation for dual Shadow CLJS mode setup
- **Minor grammar fixes** - Improved clarity in various documentation sections
- **Updated PROJECT_SUMMARY prompt** - Corrected command name in documentation

### Internal
- Fixed nREPL server function invocation
- Moved default code indexer output in preparation for dispatch-agent-content configuration
- Minor deps.edn configuration adjustments

### Contributors
Thanks to Jonathon McKitrick for documenting dual Shadow mode and Thomas Mattacchione for the PROJECT_SUMMARY prompt update.

## [v0.1.7-alpha-pre] - 2025-07-08

### Major Improvements

#### Performance Fix: Startup Speed
Fixed critical issue where ClojureMCP was scanning the home directory, causing extremely slow startup times. The fix includes:
- **Project inspection optimization**: Reduced from multiple glob calls to a single optimized call
- **Path validation**: Fixed bad paths being passed to glob functions that caused excessive filesystem scanning

#### Multi-Dialect Support
Initial support for non-JVM Clojure dialects including Babashka and Basilisp:
- **Reduced Initializing Clojure Evals**: Minimized reliance on Clojure code evaluation on startup
- **Multimethod dispatch**: Added `:nrepl-env-type` based dispatch for dialect-specific behavior
- **Configuration**: Use `:project-dir` command-line option to specify working directory for alternative dialects
- **Note**: Disable `:bash-over-nrepl` in `.clojure-mcp/config.edn` for non-JVM environments

#### Scratch Pad Persistence 
The scratch pad now automatically saves to `.clojure-mcp/scratch_pad.edn` on every change:
- **Explicit loading**: Use `scratch_pad_load` prompt to load saved data
- **Snapshot saving**: Use `scratch_pad_save_as` prompt to save snapshots to named files
- **Configurable filename**: Set `:scratch-pad-file` in config to use different filenames
- **Auto-load on startup**: Enable with `:scratch-pad-load true` in config
Many thanks to Mark Addleman for working on this.

### Added
- **Multi-dialect support infrastructure**:
  - `:nrepl-env-type` configuration parameter for dialect detection
  - Multimethod dispatch system for dialect-specific behavior
  - Support for Babashka, and Basilisp environments
- **Scratch pad persistence** with flexible configuration via `.clojure-mcp/config.edn`:
  - `:scratch-pad-load` boolean flag for auto-loading on startup (default: `false`)
  - `:scratch-pad-file` to specify filename within `.clojure-mcp/` directory (default: `"scratch_pad.edn"`)
  - Automatic saving on every change
- **New prompts for scratch pad file operations**:
  - `ACT/scratch_pad_load` prompt for loading EDN files into scratch pad
  - `ACT/scratch_pad_save_as` prompt for saving scratch pad snapshots to named files
- **Project directory override** with `:project-dir` command-line option
- **Pattern-based collapsed view for text files** in `read_file` tool:
  - Shows only lines matching patterns with 20 lines of context
  - Works with `content_pattern` or `name_pattern` parameters
- **Bash execution isolation**:
  - `:bash-over-nrepl` config option to control execution mode (default: `true`)
  - Separate nREPL session for bash commands to prevent REPL pollution
  - Output truncation for local bash execution

### Changed
- **Project inspection performance**: Optimized glob operations from multiple calls to single call
- **grep and glob_files tools** now properly launch from working directory
- **Bash tool** enhanced with configurable execution modes
- Configuration loading no longer attempts to fetch remote configs

### Documentation
- Added table of contents to README
- Hopefully improved setup instructions
- Added Shadow CLJS section to README
- Updated README with `:project-dir` usage information

### Internal
- Improved string building performance in various tools
- Enhanced configuration handling for cleaner code structure

## [v0.1.6-alpha] - 2025-06-30

### Performance Improvements
- **Optimized `clojure_inspect_project`** with ~3.8x speedup by reducing glob operations from 5 calls to 1 using brace expansion patterns 
- **Added ripgrep recommendation** to README prerequisites for better `grep` and `glob_files` performance

### Fixed
- **Issue #13**: Replaced external diff utility with native Java library (java-diff-utils) to eliminate shell command dependencies (Many thanks to @burinc for this!)
- **Project inspection file discovery** - Fixed issue where the tool was failing to find files in some configurations
- **Grep tool** now properly handles file patterns
- **Scratch pad schema** simplified to resolve issue #42, improving compatibility with tool description overrides

### Internal
- Added comprehensive test suite for diff.clj with 38 assertions covering edge cases
- Removed debug printlns from codebase
- Minor description revert for clarity

## [v0.1.5-alpha] - 2025-06-22

### Major Refactoring: Simplified Custom MCP Server Creation

The project has undergone a significant refactor to make creating custom MCP servers dramatically easier. The new pattern uses factory functions and a single entry point, reducing custom server creation to just a few lines of code.

### Added
- **Factory function pattern** for creating custom MCP servers with `make-tools`, `make-prompts`, and `make-resources` functions
- **Code indexer tool** (`clojure -X:index`) for creating condensed codebase maps showing function signatures
- **Reader conditional support** in collapsed view for `.cljc` files with platform-specific code
- **Multi-tool support** in `glob_files` with intelligent fallback (ripgrep → find → Java NIO)
- **Add-dir prompt** allowing you to add directories to allowed paths

### Changed
- **Project inspection tool** completely refactored for 97% reduction in nREPL payload by moving file operations to local execution
- **Unified read_file tool** replacing legacy implementations with pattern-based code exploration
- **Main entry point** simplified to use `core/build-and-start-mcp-server` with factory functions
- **glob_files** enhanced with better truncation messages and cross-platform compatibility
- File collection in project tool now uses local glob operations instead of remote nREPL
- Documentation updated throughout to reflect new patterns and accurate tool information

### Fixed
- **Issue #43**: grep tool now correctly handles patterns starting with `-` character
- Path existence validation in file operations
- MCP server closing behavior when client atom is not set

### Removed
- Legacy `read_file` tool implementations
- Unused collapsed file view code
- Complex manual setup patterns in favor of simplified factory approach

### Configuration
- Added `:cljfmt` option to `.clojure-mcp/config.edn` to disable code formatting when set to `false`

### Documentation
- PROJECT_SUMMARY.md updated with accurate tool names and descriptions
- Custom server guide rewritten for new simplified pattern
- Added examples for SSE transport and pattern variations

### Internal
- File timestamp behavior improved with better logging
- Code organization enhanced with docstrings for core functions
- Examples updated to use new interface patterns

## [v0.1.4-alpha] - 2025-06-11

### The scratch_pad Tool: Persistent AI Workspace

After a bunch of refinements the scratch_pad tool has matured into a
very interesting tool - a freeform **JSON data structure** shared
across all chat sessions in chat client.

#### What It Does

- **Persistent memory**: Data survives across conversations
- **Flexible storage**: Any JSON data (objects, arrays, strings, numbers)
- **Path operations**: Use `set_path`/`get_path`/`delete_path` for precise data manipulation
- **AI workspace**: Serves as both thinking tool and progress tracker

#### Structured Planning

For complex features, use the new `plan-and-execute` prompt which leverages scratch_pad to:
- Research problems thoroughly
- Break down tasks into manageable subtasks  
- Track progress with structured todo lists
- Maintain context throughout development

### Added
- **Ripgrep (rg) support** in grep tool with intelligent fallback hierarchy (rg > grep > Java)
- **Smart path operations** in scratch_pad tool for automatic string-to-number conversion and data structure initialization

### Changed
- **Tool rename**: `fs_grep` → `grep` for better consistency
- Enhanced scratch_pad with smart path munging for vector indices
- Improved error handling for streaming operations with null parameters

### Fixed
- Streaming errors when receiving null parameters in MCP operations
- Schema validation errors in tool operations

### Internal
- General code linting and cleanup across multiple files
- Improved documentation and README content

## [v0.1.3-alpha] - 2025-06-09

### Added
- **Configurable file timestamp tracking** via `:write-file-guard` option (`:full-read`, `:partial-read`, or `false`)
- File existence validation in `unified_read_file` tool
- FAQ.md documenting file timestamp tracking behavior
- Docker support (`:dkr-nrepl` alias)
- `plan-and-execute` prompt for structured planning workflows
- Test coverage for new features

### Changed
- Enhanced scratch_pad prompt to encourage planning usage
- Clojure files always read as text format
- nREPL aliases now bind to 0.0.0.0 for network access
- Cleaned up unused prompt definitions

### Documentation
- FAQ with table of contents explaining timestamp tracking
- Updated system prompts and README with Wiki link

### Internal
- Added configuration helper functions (`get-write-file-guard`, `write-guard?`)
- File validation utilities (`path-exists?`)
