# Clojure MCP Project Summary

## Project Status

**⚠️ Alpha Software** - This project is in early development and rapidly evolving. While invaluable for Clojure development workflows, expect breaking changes, rough edges, and incomplete documentation. Community contributions are actively welcomed for bug reports, feature suggestions, documentation improvements, and code contributions.

## Project Overview

Clojure MCP is a Model Context Protocol (MCP) server that enables AI assistants (like Claude) to interact directly with a Clojure REPL. It provides a collaborative, REPL-driven development workflow between humans and LLMs. The core philosophy is "tiny steps with high quality rich feedback" for effective development.

**Recent Major Refactoring**: The project has been refactored to separate the core MCP API from its consumption, enabling easier customization and reuse. The new pattern uses factory functions (`make-tools`, `make-prompts`, `make-resources`) and a single entry point (`core/build-and-start-mcp-server`) for creating custom MCP servers.

The project allows AI assistants to:
- Evaluate Clojure code and see immediate results
- Incrementally develop solutions with step-by-step verification
- Navigate and explore namespaces and symbols
- Edit Clojure files with proper formatting and structure-aware operations
- Access documentation and source code
- Test code directly in the REPL environment

## Key File Paths and Descriptions

### Core System Files

- `/src/clojure_mcp/core.clj`: **Refactored** - Provides the reusable API for building MCP servers with the main `build-and-start-mcp-server` function
- `/src/clojure_mcp/main.clj`: **Refactored** - Example implementation showing how to consume the core API with factory functions
- `/src/clojure_mcp/nrepl.clj`: nREPL client implementation for connecting to Clojure REPL
- `/src/clojure_mcp/tool_system.clj`: Defines the multimethod-based architecture for tools
- `/src/clojure_mcp/prompts.clj`: Manages system prompts for AI assistants
- `/src/clojure_mcp/resources.clj`: Manages resources to be exposed to AI assistants
- `/src/clojure_mcp/config.clj`: **Enhanced** - Configuration system supporting `.clojure-mcp/config.edn` files
- `/src/clojure_mcp/linting.clj`: Code quality and formatting utilities
- `/src/clojure_mcp/sse_core.clj`: Server-Sent Events transport implementation
- `/src/clojure_mcp/sse_main.clj`: Example SSE server using the new pattern

### Tool Implementations

#### Active Tools (used in main.clj)

- `/src/clojure_mcp/tools/eval/`: Code evaluation tools
- `/src/clojure_mcp/tools/unified_read_file/`: Enhanced file reading with pattern-based code exploration
  - `tool.clj`: Main tool implementation with MCP integration
  - `pattern_core.clj`: Core pattern matching functionality for Clojure code analysis
  - `file_timestamps.clj`: Track file read/modification timestamps for safety
- `/src/clojure_mcp/tools/form_edit/`: Structure-aware Clojure code editing
  - `combined_edit_tool.clj`: Unified form editing tool
  - `tool.clj`: S-expression replacement tool
- `/src/clojure_mcp/tools/file_edit/`: Basic file editing operations
- `/src/clojure_mcp/tools/file_write/`: File writing operations
- `/src/clojure_mcp/tools/directory_tree/`: Filesystem navigation
- `/src/clojure_mcp/tools/grep/`: Content searching in files
- `/src/clojure_mcp/tools/glob_files/`: Pattern-based file finding
- `/src/clojure_mcp/tools/project/`: Project structure analysis
- `/src/clojure_mcp/tools/code_critique/`: Code quality feedback
- `/src/clojure_mcp/tools/think/`: Reflective thinking tool for AI assistants
- `/src/clojure_mcp/tools/bash/`: Shell command execution
  - **NEW**: Uses a separate nREPL session for isolation
  - Each bash tool instance creates its own session on initialization
  - Commands execute in an isolated environment from the main REPL
  - Supports both nREPL and local execution modes via config
- `/src/clojure_mcp/tools/dispatch_agent/`: Agent dispatching for complex tasks
- `/src/clojure_mcp/tools/architect/`: Technical planning and architecture assistance
- `/src/clojure_mcp/tools/scratch_pad/`: Persistent scratch pad for inter-tool communication
  - `core.clj`: Core functionality for data storage and retrieval
  - `tool.clj`: MCP integration with path-based operations (set_path, get_path, delete_path, persistence_config, status)
  - `config.clj`: Configuration file management for dual-mode persistence (config file + runtime tool)
  - `truncate.clj`: Pretty-printing with depth truncation

#### Unused Tools (moved to other_tools/)

**Note**: These tools have been moved to `/src/clojure_mcp/other_tools/` to clearly separate them from actively used tools. They remain fully functional with passing tests but are not registered in `main.clj`. This organizational change helps maintain a cleaner codebase by distinguishing between essential tools and potentially unnecessary ones.

- `/src/clojure_mcp/other_tools/create_directory/`: Tool for creating directories
- `/src/clojure_mcp/other_tools/list_directory/`: Tool for listing directory contents
- `/src/clojure_mcp/other_tools/move_file/`: Tool for moving/renaming files
- `/src/clojure_mcp/other_tools/namespace/`: Clojure namespace exploration tools
  - Includes: `current_namespace`, `clojure_list_namespaces`, `clojure_list_vars_in_namespace`
- `/src/clojure_mcp/other_tools/symbol/`: Symbol information and documentation tools
  - Includes: `symbol_completions`, `symbol_metadata`, `symbol_documentation`, `source_code`, `symbol_search`

All unused tools have corresponding test files moved to `/test/clojure_mcp/other_tools/` with updated namespace declarations.

### Example Main Files

- `/src/clojure_mcp/main_examples/shadow_main.clj`: Example custom server for Shadow CLJS support
- `/src/clojure_mcp/main_examples/figwheel_main.clj`: Example custom server for Figwheel Main support

### Resource Directories

- `/resources/prompts/`: System prompts for AI assistants
- `/resources/prompts/system/`: Core system prompts
- `/resources/agent/`: Agent-specific resources
- `/resources/configs/`: Configuration examples
- `/resources/logback.xml`: Logging configuration file

## Dependencies and Versions

### Core Dependencies

- `org.clojure/clojure` (1.12.1): The Clojure language
- `io.modelcontextprotocol.sdk/mcp` (0.10.0): Model Context Protocol SDK
- `nrepl/nrepl` (1.3.1): Network REPL server for Clojure
- `rewrite-clj/rewrite-clj` (1.1.47): Library for parsing and transforming Clojure code
- `dev.weavejester/cljfmt` (0.13.1): Clojure code formatting
- `clj-kondo/clj-kondo` (2024.03.13): Static analyzer and linter for Clojure
- `org.clojure/tools.logging` (1.3.0): Logging abstraction for Clojure
- `ch.qos.logback/logback-classic` (1.4.14): Logback implementation for SLF4J

### AI Integration Dependencies

- `dev.langchain4j/langchain4j` (1.0.1): Java library for LLM integration
- `dev.langchain4j/langchain4j-anthropic` (1.0.1-beta6): Anthropic-specific integration
- `dev.langchain4j/langchain4j-google-ai-gemini` (1.0.1-beta6): Google Gemini integration
- `dev.langchain4j/langchain4j-open-ai` (1.0.1): OpenAI integration
- `pogonos/pogonos` (0.2.1): Mustache templating for prompts

### Additional Dependencies

- `org.clojars.oakes/parinfer` (0.4.0): Parenthesis inference for Clojure
- `org.apache.tika/tika-core` (3.2.0): Content detection and extraction
- `org.clojure/data.json` (2.5.1): JSON parsing and generation
- `org.clojure/tools.cli` (1.1.230): Command line argument parsing

## Configuration System

The project supports project-specific configuration through `.clojure-mcp/config.edn` files:

### Configuration Location
```
your-project/
├── .clojure-mcp/
│   └── config.edn
├── src/
└── deps.edn
```

### Configuration Options
- `allowed-directories`: Controls which directories MCP tools can access (security)
- `emacs-notify`: Boolean flag for Emacs integration
- `write-file-guard`: Controls file timestamp tracking behavior (default: `:full-read`)
  - `:full-read` - Only full reads update timestamps (safest, default)
  - `:partial-read` - Both full and collapsed reads update timestamps
  - `false` - Disables timestamp checking entirely
- `cljfmt`: Boolean flag to enable/disable cljfmt formatting in editing pipelines (default: `true`)
  - `true` - Applies cljfmt formatting to edited files (default behavior)
  - `false` - Disables formatting, preserving exact whitespace and formatting
- `bash-over-nrepl`: Boolean flag to control bash command execution mode (default: `true`)
  - `true` - Execute bash commands over nREPL connection (default behavior)
  - `false` - Execute bash commands locally on the MCP server
- `scratch-pad-load`: Boolean flag to enable/disable scratch pad persistence (default: `false`)
  - `true` - Loads existing data on startup and saves changes to disk
  - `false` - Scratch pad operates in memory only, no file persistence
  - Can be configured via config file OR runtime using `persistence_config` operation
- `scratch-pad-file`: Filename for scratch pad persistence (default: `"scratch_pad.edn"`)
  - Specifies the filename within `.clojure-mcp/` directory
  - Only used when `scratch-pad-load` is `true`
  - Can be modified at runtime using `persistence_config` operation

### Example Configuration
```edn
{:allowed-directories ["." "src" "test" "resources" "../sibling-project"]
 :emacs-notify false
 :write-file-guard :full-read
 :cljfmt true
 :bash-over-nrepl true
 :scratch-pad-load false
 :scratch-pad-file "scratch_pad.edn"}
```

### Path Resolution and Security
- Relative paths resolved from project root
- Absolute paths used as-is
- All file operations validated against allowed directories
- Project root automatically included in allowed directories

## Available Tools

The following tools are available in the default configuration (`main.clj`):

### Read-Only Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `LS` | Returns a recursive tree view of files and directories | Exploring project structure |
| `read_file` | Smart file reader with pattern-based exploration for Clojure files | Reading files with collapsed view, pattern matching |
| `grep` | Fast content search tool that works with any codebase size | Finding files containing specific patterns |
| `glob_files` | Fast file pattern matching tool that works with any codebase size | Finding files by name patterns like `*.clj` |
| `think` | Use the tool to think about something | Planning approaches, organizing thoughts |

### Code Evaluation

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `clojure_eval` | Takes a Clojure Expression and evaluates it in the current namespace | Testing expressions, REPL-driven development |
| `bash` | Execute bash shell commands on the host system | Running tests, git commands, file operations |

### File Editing Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `clojure_edit` | Edits a top-level form in a Clojure file using the specified operation | Replacing/inserting functions, handling defmethod |
| `clojure_edit_replace_sexp` | Edits a file by finding and replacing specific s-expressions | Changing specific s-expressions within functions |
| `file_edit` | Edit a file by replacing a specific text string with a new one | Simple text replacements |
| `file_write` | Write a file to the local filesystem | Creating new files, overwriting with validation |

### Introspection

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `clojure_inspect_project` | Analyzes and provides detailed information about a Clojure project's structure | Understanding project organization, dependencies |

### Agent Tools (Require API Keys)

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `dispatch_agent` | Launch a new agent that has access to read-only tools | Multi-step file exploration and analysis |
| `architect` | Your go-to tool for any technical or coding task | System design, architecture decisions |

### Experimental Tools

| Tool Name | Description | Example Usage |
|-----------|-------------|---------------|
| `scratch_pad` | A persistent scratch pad for storing structured data between tool calls | Task tracking, intermediate results, inter-agent communication, with dual-mode persistence configuration |
| `code_critique` | Starts an interactive code review conversation that provides constructive feedback on your Clojure code | Iterative code quality improvement |

## Tool Examples

### Code Evaluation

```clojure
clojure_eval:
  Input: (+ 1 2)
  Output: => 3
```

### File Operations

```clojure
read_file:
  Input: {:path "/path/to/file.clj", 
          :collapsed true,
          :name_pattern "validate.*", 
          :content_pattern "try|catch"}
  Output: File contents with pattern-based collapsed view
  
file_edit:
  Input: {:file_path "/path/to/file.clj", 
          :old_string "(defn old", 
          :new_string "(defn new"}
  Output: Diff showing changes made
```

### Clojure-Specific Editing

```clojure
clojure_edit:
  Input: {:file_path "/path/to/file.clj", 
          :form_identifier "my-func",
          :form_type "defn",
          :content "(defn my-func [x] (* x 2))", 
          :operation "replace"}
  Output: Diff showing syntax-aware function replacement

clojure_edit_replace_sexp:
  Input: {:file_path "/path/to/file.clj", 
          :match_form "(+ x 2)", 
          :new_form "(+ x 10)"}
  Output: Diff showing s-expression replacement
```

### Scratch Pad - Persistent Data Storage

```clojure
scratch_pad:
  op: set_path
  path: ["todos", 0]
  value: {task: "Write tests", done: false}
  explanation: Adding first task
  Output: Stored value at path ["todos", 0]

scratch_pad:
  op: get_path
  path: ["todos", 0]
  explanation: Checking first task
  Output: Value at ["todos", 0]: {task: "Write tests", done: false}

scratch_pad:
  op: persistence_config
  enabled: true
  filename: "my_workspace.edn"
  explanation: Enable persistence with custom filename
  Output: Persistence enabled (file: my_workspace.edn)

scratch_pad:
  op: status
  explanation: Check persistence status
  Output: Scratch Pad Status with file info, size, and entry count
```

## Architecture and Design Patterns

### Core Architecture Components

1. **MCP Server**: Entry point that exposes tools to AI assistants
2. **nREPL Client**: Connects to the Clojure REPL for code evaluation
3. **Tool System**: Extensible multimethod-based architecture for defining tools
4. **Prompt System**: Provides context and guidance to AI assistants
5. **Factory Pattern**: New pattern using factory functions for tools, prompts, and resources

### Key Implementation Patterns

1. **Factory Function Pattern**: The refactored architecture uses factory functions:
   - `make-tools`: `(fn [nrepl-client-atom working-directory] ...)` returns seq of tools
   - `make-prompts`: `(fn [nrepl-client-atom working-directory] ...)` returns seq of prompts
   - `make-resources`: `(fn [nrepl-client-atom working-directory] ...)` returns seq of resources
   - All components created through `core/build-and-start-mcp-server`

2. **Multimethod Dispatch**: The tool system uses multimethods for extensibility:
   - `tool-name`: Determines the name of a tool
   - `tool-description`: Provides human-readable description
   - `tool-schema`: Defines the input/output schema
   - `validate-inputs`: Validates tool inputs
   - `execute-tool`: Performs the actual operation
   - `format-results`: Formats the results for the AI

3. **Core/Tool Separation**: Each tool follows a pattern:
   - `core.clj`: Pure functionality without MCP dependencies
   - `tool.clj`: MCP integration layer using the tool system

4. **Structured Clojure Code Editing**: Uses rewrite-clj to:
   - Parse Clojure code into zipper structure
   - Perform structure-aware transformations
   - Maintain proper formatting and whitespace
   - Key advantages over generic text editing:
     - Pattern-based matching with form identifiers
     - Targets forms by type and name rather than text matching
     - Structure-aware matching ignores whitespace differences
     - Provides early syntax validation for parenthesis balancing
     - Handles special forms like defmethod with dispatch values correctly

5. **REPL-Driven Development**: All tools designed to support:
   - Incremental development
   - Immediate feedback
   - Step-by-step verification

6. **Pattern-Based Code Exploration**: The `read_file` tool supports:
   - Regular expression matching for function names with `name_pattern`
   - Content-based pattern matching with `content_pattern`
   - Focused code reading with collapsed view and selective expansion
   - Markdown-formatted output with usage hints

7. **File Timestamp Tracking**: Ensures file operation safety:
   - Tracks when files are last read or modified
   - Prevents editing files that have been externally modified
   - Automatically updates timestamps after write operations
   - Enables multiple sequential edits after a single read
   - Uses canonical paths consistently for reliable file identification

8. **Persistent State Management**: The `scratch_pad` tool provides:
   - Global atom-based storage accessible across all tool invocations
   - Path-based data structure manipulation using `set_path`/`get_path`/`delete_path` operations
   - Direct storage of JSON-compatible values without parsing
   - Path elements as arrays of strings and numbers
   - Tree visualization for debugging and inspection
   - Pretty-printed output with truncation at depth 3 for readability
   - Dual-mode persistence configuration:
     - **Config File Based**: Enable persistence via `.clojure-mcp/config.edn` file
     - **Runtime Tool Based**: Use `persistence_config` operation to enable/disable and configure filename
     - Tool-based changes automatically update config file for session persistence
   - Lock file management to prevent data corruption from multiple instances
   - Error handling for corrupted files with user notification
   - Status operation showing persistence state, file info, and statistics

## Development Workflow Recommendations

1. **Setup and Configuration**:
   - Configure Claude Desktop with the Clojure MCP server
   - Set up file system and Git integration if needed

2. **REPL-Driven Development**:
   - Start with small, incremental steps
   - Evaluate code in the REPL to verify correctness
   - Save working code to files when verified

3. **Tool Usage Best Practices**:
   - Use `clojure_eval` for testing code snippets
   - Use `clojure_edit` and `clojure_edit_replace_sexp` for syntax-aware code editing
   - Always read a file with `read_file` before editing if it might have been modified externally
   - After using `file_write`, you can immediately edit the file without reading it first
   - Use `scratch_pad` for:
     - Tracking tasks and todos across tool invocations
     - Storing intermediate computation results
     - Building up complex data structures incrementally
     - Sharing context between different agents or tool calls
     - Enable persistence via config file or `persistence_config` operation for data that should survive sessions

4. **Logging System**:
   - Uses `clojure.tools.logging` with Logback backend
   - Logs are written to `logs/clojure-mcp.log` with daily rotation
   - Configure log levels in `resources/logback.xml`
   - Server startup/shutdown and errors are logged automatically

5. **Project Maintenance**:
   - Run all tests with `clojure -X:test`
   - Run specific test files with `clojure -X:test :includes '["test-file-name"]'`
     - Note: Use `:includes` (plural) for specific test patterns, not `:include`
     - Example: `clojure -X:test :includes '["persistence-test"]'`
   - Update this project summary after significant changes
   
6. **Testing Best Practices**:
   - Use the provided test utilities in `clojure-mcp.tools.test-utils`
   - Always use canonical paths (`.getCanonicalPath()`) when working with file operations
   - Register files with the timestamp tracker before attempting to modify them in tests
   - Include small delays between timestamp operations to ensure different timestamps

7. **Pattern-Based Code Exploration**:
   - Use the enhanced `read_file` tool for efficient codebase navigation
   - Combine `name_pattern` and `content_pattern` to focus on relevant code
   - Find specific defmethod implementations using their dispatch values
   - Examples:
     - Find all validation functions: `{:name_pattern "validate.*"}`
     - Find error handling: `{:content_pattern "try|catch|throw"}`
     - Find where a specific function is used: `{:content_pattern "some-important-function"}`
     - Find a specific defmethod: `{:name_pattern "area :rectangle"}`

## Creating Custom MCP Servers

The refactored architecture makes it simple to create custom MCP servers:

### Minimal Example

```clojure
(ns my-company.mcp-server
  (:require [clojure-mcp.core :as core]
            [clojure-mcp.main :as main]))

(defn start-mcp-server [opts]
  (core/build-and-start-mcp-server
   opts
   {:make-tools-fn main/make-tools
    :make-prompts-fn main/make-prompts
    :make-resources-fn main/make-resources}))
```

### Custom Tools Example

```clojure
(defn make-tools [nrepl-client-atom working-directory]
  (concat
   (main/make-tools nrepl-client-atom working-directory)
   [(my-custom-tool/create-tool nrepl-client-atom)]))
```

See `/doc/custom-mcp-server.md` for comprehensive documentation on creating custom servers.

## Extension Points

1. **Adding New Tools**:
   - Create a new tool namespace in `/src/clojure_mcp/tools/` for active tools
   - Implement the required multimethods from `tool-system`
   - Register the tool in `main.clj` within the `make-tools` function
   - Note: Tools in `/src/clojure_mcp/other_tools/` are not automatically registered

2. **Creating Custom Servers**:
   - Define factory functions for tools, prompts, and resources
   - Call `core/build-and-start-mcp-server` with your factories
   - See example implementations in `main_examples/`

3. **Enhancing Existing Tools**:
   - Most tools follow a pipeline architecture that can be modified by adding new steps
   - Pipeline steps follow a thread-first pattern with error short-circuiting

4. **Re-activating Unused Tools**:
   - Tools in `/src/clojure_mcp/other_tools/` can be re-activated by:
     - Moving them back to `/src/clojure_mcp/tools/`
     - Updating namespace declarations
     - Adding them to the imports and `make-tools` function in `main.clj`
   - Alternatively, create custom MCP servers using these tools via the core API

5. **Alternative Transports**:
   - Use `sse-core/build-and-start-mcp-server` for SSE transport
   - See `sse-main.clj` for an example implementation

## Recent Organizational Changes

**New Factory Function Pattern**: The project has been refactored to use a cleaner pattern for creating custom MCP servers:
- Factory functions (`make-tools`, `make-prompts`, `make-resources`) with consistent signatures
- Single entry point via `core/build-and-start-mcp-server`
- Simplified custom server creation
- Example implementations in `main_examples/` directory

**Scratch Pad Tool Addition**: Added a new `scratch_pad` tool for persistent data storage across tool invocations. This tool enables:
- Inter-agent communication through shared state
- Task tracking with structured todo lists
- Building complex data structures incrementally
- Path-based data manipulation using `set_path`/`get_path`/`delete_path` operations
- Direct storage of JSON-compatible values
- **Dual-mode persistence configuration**: Configure persistence via config file OR runtime tool operations
- Runtime configuration operations (`persistence_config`, `status`) with immediate effect
- Automatic config file updates when using tool-based configuration

**Tool Reorganization**: To improve codebase maintainability, unused tools have been moved to `/src/clojure_mcp/other_tools/`. This separation clarifies which tools are actively used in the main MCP server (`main.clj`) versus those that remain available but are not currently essential.

This project summary is designed to provide AI assistants with a quick understanding of the Clojure MCP project structure and capabilities, enabling more effective assistance with minimal additional context. The project continues to evolve with improvements focused on making it easier to create custom MCP servers while maintaining compatibility with a wide range of LLMs.
