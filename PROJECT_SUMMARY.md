# Clojure MCP Project Summary

## Project Status

**⚠️ Alpha Software** - This project is in early development and rapidly evolving. While invaluable for Clojure development workflows, expect breaking changes, rough edges, and incomplete documentation. Community contributions are actively welcomed for bug reports, feature suggestions, documentation improvements, and code contributions.

## Project Overview

Clojure MCP is a Model Context Protocol (MCP) server that enables AI assistants (like Claude) to interact directly with a Clojure REPL. It provides a collaborative, REPL-driven development workflow between humans and LLMs. The core philosophy is "tiny steps with high quality rich feedback" for effective development.

**Recent Major Refactoring**: The project has been refactored to separate the core MCP API from its consumption, enabling easier customization and reuse.

The project allows AI assistants to:
- Evaluate Clojure code and see immediate results
- Incrementally develop solutions with step-by-step verification
- Navigate and explore namespaces and symbols
- Edit Clojure files with proper formatting and structure-aware operations
- Access documentation and source code
- Test code directly in the REPL environment

## Key File Paths and Descriptions

### Core System Files

- `/src/clojure_mcp/core.clj`: **Refactored** - Now provides the reusable API for building MCP servers with convenience higher-level functions
- `/src/clojure_mcp/main.clj`: **New** - Example implementation showing how to consume the core API to build the actual Clojure MCP server
- `/src/clojure_mcp/nrepl.clj`: nREPL client implementation for connecting to Clojure REPL
- `/src/clojure_mcp/tool_system.clj`: Defines the multimethod-based architecture for tools
- `/src/clojure_mcp/repl_tools.clj`: Central registry for all available tools
- `/src/clojure_mcp/prompts.clj`: Manages system prompts for AI assistants
- `/src/clojure_mcp/resources.clj`: Manages resources to be exposed to AI assistants
- `/src/clojure_mcp/config.clj`: **Enhanced** - Configuration system supporting `.clojure-mcp/config.edn` files
- `/src/clojure_mcp/linting.clj`: Code quality and formatting utilities

### Tool Implementations

#### Active Tools (used in main.clj)

- `/src/clojure_mcp/tools/eval/`: Code evaluation tools
- `/src/clojure_mcp/tools/read_file/`: File reading utilities
  - `core.clj`: Core file reading functionality
  - `tool.clj`: Tool implementation with MCP integration
  - `file_timestamps.clj`: Track file read/modification timestamps for safety
- `/src/clojure_mcp/tools/form_edit/`: Structure-aware Clojure code editing
- `/src/clojure_mcp/tools/file_edit/`: Basic file editing operations
- `/src/clojure_mcp/tools/unified_file_edit/`: Combined file editing capabilities
- `/src/clojure_mcp/tools/unified_read_file/`: Enhanced file reading with pattern-based code exploration
  - `tool.clj`: Main tool implementation with MCP integration
  - `pattern_core.clj`: Core pattern matching functionality for Clojure code analysis
- `/src/clojure_mcp/tools/directory_tree/`: Filesystem navigation
- `/src/clojure_mcp/tools/grep/`: Content searching in files
- `/src/clojure_mcp/tools/glob_files/`: Pattern-based file finding
- `/src/clojure_mcp/tools/project/`: Project structure analysis
- `/src/clojure_mcp/tools/code_critique/`: Code quality feedback
- `/src/clojure_mcp/tools/think/`: Reflective thinking tool for AI assistants
- `/src/clojure_mcp/tools/bash/`: Shell command execution
- `/src/clojure_mcp/tools/dispatch_agent/`: Agent dispatching for complex tasks
- `/src/clojure_mcp/tools/architect/`: Technical planning and architecture assistance
- `/src/clojure_mcp/tools/file_write/`: File writing operations
- `/src/clojure_mcp/tools/scratch_pad/`: **New** - Persistent scratch pad for inter-tool communication
  - `core.clj`: Core functionality for data storage and retrieval
  - `tool.clj`: MCP integration with path-based operations (assoc_in, get_in, dissoc_in)

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

### Resource Directories

- `/resources/prompts/`: System prompts for AI assistants
- `/resources/prompts/system/`: Core system prompts
- `/resources/agent/`: Agent-specific resources
- `/resources/configs/`: Configuration examples
- `/resources/logback.xml`: Logging configuration file

## Dependencies and Versions

### Core Dependencies

- `org.clojure/clojure` (1.11.1): The Clojure language
- `io.modelcontextprotocol.sdk/mcp` (0.9.0): Model Context Protocol SDK
- `nrepl/nrepl` (1.3.1): Network REPL server for Clojure
- `rewrite-clj/rewrite-clj` (1.1.47): Library for parsing and transforming Clojure code
- `dev.weavejester/cljfmt` (0.13.1): Clojure code formatting
- `clj-kondo/clj-kondo` (2024.03.13): Static analyzer and linter for Clojure
- `org.clojure/tools.logging` (1.3.0): Logging abstraction for Clojure
- `ch.qos.logback/logback-classic` (1.4.14): Logback implementation for SLF4J

### AI Integration Dependencies

- `dev.langchain4j/langchain4j` (1.0.0-beta3): Java library for LLM integration
- `dev.langchain4j/langchain4j-anthropic` (1.0.0-beta3): Anthropic-specific integration
- `pogonos/pogonos` (0.2.1): Mustache templating for prompts

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

### Example Configuration
```edn
{:allowed-directories ["." "src" "test" "resources" "../sibling-project"]
 :emacs-notify false}
```

### Path Resolution and Security
- Relative paths resolved from project root
- Absolute paths used as-is
- All file operations validated against allowed directories
- Project root automatically included in allowed directories

## Available Tools and Examples

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
          :content_pattern "try|catch",
          :include_comments false}
  Output: File contents with pattern-based collapsed view
  
edit_file:
  Input: {:file_path "/path/to/file.clj", :old_string "(defn old", :new_string "(defn new"}
  Output: Diff showing changes made
```

### Clojure-Specific Editing

The project has transitioned to using a unified `clojure_edit` tool which provides more powerful pattern-based editing capabilities while simplifying the interface. The older form-specific tools are still available for compatibility with other LLMs.

```clojure
clojure_edit:
  Input: {:file_path "/path/to/file.clj", 
          :sexp_pattern "(defn my-func _*)", 
          :raw_content "(defn my-func [x] (* x 2))", 
          :operation "replace"}
  Output: Diff showing syntax-aware function replacement
  
clojure_edit:
  Input: {:file_path "/path/to/file.clj", 
          :sexp_pattern "(defn my-func _*)", 
          :raw_content "(def magic-multiplier 2)", 
          :operation "insert_before"}
  Output: Diff showing insertion before the matched pattern
  
clojure_edit:
  Input: {:file_path "/path/to/file.clj", 
          :sexp_pattern "(defn my-func _*)", 
          :raw_content "(deftest my-func-test (is (= 4 (my-func 2))))", 
          :operation "insert_after"}
  Output: Diff showing insertion after the matched pattern

# Examples with namespace-qualified forms and defmethod

clojure_edit:
  Input: {:file_path "/path/to/file.clj", 
          :sexp_pattern "(defmethod tool-system/validate-inputs :clojure-eval _*)", 
          :raw_content "(defmethod tool-system/validate-inputs :clojure-eval [_ inputs]\n  (validate-clojure-eval-inputs inputs))", 
          :operation "replace"}
  Output: Diff showing replacement of a specific multimethod implementation
```

### Code Search and Navigation

```clojure
glob_files:
  Input: {:pattern "**/*.clj"}
  Output: List of matching file paths
  
fs_grep:
  Input: {:pattern "defn my-func"}
  Output: List of files containing the pattern

# Note: symbol_search tool has been moved to other_tools/ and is not actively used
# but remains available for specialized namespace/symbol exploration if needed
```

### Project Information

```clojure
clojure_inspect_project:
  Input: {}
  Output: Detailed project structure information
```

### Scratch Pad - Persistent Data Storage

**New tool for inter-tool communication and task tracking**

The `scratch_pad` tool provides persistent storage for structured data between tool calls, enabling:
- Task tracking with todo lists
- Storing intermediate results
- Sharing data between agents
- Building up complex data structures incrementally

```clojure
# Basic operations
scratch_pad:
  Operations: assoc_in, get_in, dissoc_in, tree_view
  Path elements: Array of strings or numbers (no parsing needed)
  Values: Any JSON-compatible value (objects, arrays, strings, numbers, booleans, null)

# Adding todo items
scratch_pad:
  op: assoc_in
  path: ["todos" 0]
  value: {task: "Write tests", done: false}
  todo: "todos"
  explanation: Adding first task
  Output: Stored value at path ["todos" 0] (todo: todos)

# Adding multiple todo items at once
scratch_pad:
  op: assoc_in
  path: ["todos"]
  value: {
    0: {task: "Write tests", done: false, priority: "high"},
    1: {task: "Review PR", done: false, priority: "high"},
    2: {task: "Update docs", done: false, priority: "medium"}
  }
  todo: "todos"
  explanation: Adding multiple todos at once

# Checking off tasks
scratch_pad:
  op: assoc_in
  path: ["todos" 0 "done"]
  value: true
  todo: "todos"
  explanation: Completed writing tests
  Output: Stored value at path ["todos" 0 "done"] (todo: todos)

# Viewing all data
scratch_pad:
  op: tree_view
  explanation: Checking current state
  Output: Tree view with formatted structure:
    {"todos"
     {0
      {"task" "Write tests",
       "done" true}}}

# Retrieving specific values
scratch_pad:
  op: get_in
  path: ["todos" 0]
  explanation: Checking first task details
  Output: Value at ["todos" 0]: {task: "Write tests", done: true}

# Removing data
scratch_pad:
  op: dissoc_in
  path: [todos 0]
  explanation: Removing completed task
  Output: Removed value at path ["todos" 0]

# Recommended todo schema:
{
  task: "Description",
  done: false,
  priority: "high", // optional: "high", "medium", "low"
  context: "Additional details" // optional
}
```

## Collapsed View and Pattern-Based Code Exploration

The `read_file` tool provides a powerful code exploration feature through its pattern-based collapsed view:

### Key Features

1. **Collapsed View**: Shows only function signatures by default, making large files navigable
2. **Pattern Matching**: Expands functions matching specific patterns
   - `name_pattern`: Regex to match function names (e.g., "validate.*")
   - `content_pattern`: Regex to match function content (e.g., "try|catch")
3. **Comment Control**: Option to include/exclude comments with `include_comments`
4. **Markdown Formatting**: Results are formatted in markdown with:
   - File header and pattern information
   - Syntax-highlighted code blocks
   - Usage tips for further exploration

### Implementation

The implementation uses rewrite-clj to:
1. Parse Clojure code into zipper structures
2. Collect top-level forms with metadata
3. Apply regex pattern matching to function names and content
4. Generate a collapsed view with selected expansions
5. Support defmethod forms with special handling for:
   - Keyword dispatch values (e.g., `:rectangle`)
   - Vector dispatch values (e.g., `[:feet :inches]`)
   - Namespace-qualified multimethod names (e.g., `tool-system/validate-inputs`)

### Usage Examples

```clojure
;; Basic collapsed view of a file
{:path "/path/to/file.clj", :collapsed true}

;; Find all functions with 'validate' in their names
{:path "/path/to/file.clj", :name_pattern "validate"}

;; Find all error handling code
{:path "/path/to/file.clj", :content_pattern "try|catch|throw"}

;; Combined patterns - find validation functions that handle errors
{:path "/path/to/file.clj", :name_pattern "validate", :content_pattern "try|catch"}

;; Include comments in search
{:path "/path/to/file.clj", :content_pattern "TODO", :include_comments true}

;; View entire file without collapsing
{:path "/path/to/file.clj", :collapsed false}

;; Working with defmethod forms
{:path "/path/to/file.clj", :name_pattern "area :rectangle"}                   ;; Find specific dispatch value
{:path "/path/to/file.clj", :name_pattern "dispatch-with-vector \\[:feet :inches\\]"} ;; Find vector dispatch value
{:path "/path/to/file.clj", :name_pattern "tool-system/validate-inputs"}       ;; Find namespaced multimethods
```

## Architecture and Design Patterns

### Core Architecture Components

1. **MCP Server**: Entry point that exposes tools to AI assistants
2. **nREPL Client**: Connects to the Clojure REPL for code evaluation
3. **Tool System**: Extensible multimethod-based architecture for defining tools
4. **Prompt System**: Provides context and guidance to AI assistants

### Key Implementation Patterns

1. **Multimethod Dispatch**: The tool system uses multimethods for extensibility:
   - `tool-name`: Determines the name of a tool
   - `tool-description`: Provides human-readable description
   - `tool-schema`: Defines the input/output schema
   - `validate-inputs`: Validates tool inputs
   - `execute-tool`: Performs the actual operation
   - `format-results`: Formats the results for the AI

2. **Core/Tool Separation**: Each tool follows a pattern:
   - `core.clj`: Pure functionality without MCP dependencies
   - `tool.clj`: MCP integration layer using the tool system

3. **Structured Clojure Code Editing**: Uses rewrite-clj to:
   - Parse Clojure code into zipper structure
   - Perform structure-aware transformations
   - Maintain proper formatting and whitespace
   - Key advantages over generic text editing:
     - Pattern-based matching with wildcard symbols (`_?` for single form, `_*` for multiple forms)
     - Targets forms by pattern rather than requiring exact text matching
     - Structure-aware matching ignores troublesome whitespace differences
     - Provides early syntax validation for parenthesis balancing
     - Validates that patterns match exactly one form to prevent ambiguous edits
     - Gives specific error messages for easier troubleshooting
     - Handles special forms like defmethod with dispatch values correctly

4. **REPL-Driven Development**: All tools designed to support:
   - Incremental development
   - Immediate feedback
   - Step-by-step verification

5. **Pattern-Based Code Exploration**: The `read_file` tool supports:
   - Regular expression matching for function names with `name_pattern`
   - Content-based pattern matching with `content_pattern`
   - Focused code reading with collapsed view and selective expansion
   - Markdown-formatted output with usage hints

6. **File Timestamp Tracking**: Ensures file operation safety:
   - Tracks when files are last read or modified
   - Prevents editing files that have been externally modified
   - Automatically updates timestamps after write operations
   - Enables multiple sequential edits after a single read
   - Uses canonical paths consistently for reliable file identification:
     - Handles path differences between `.getAbsolutePath()` and `.getCanonicalPath()` (on macOS `/var/...` vs `/private/var/...`)
     - Ensures timestamp lookups work correctly regardless of how paths are specified

7. **Persistent State Management**: The `scratch_pad` tool provides:
   - Global atom-based storage accessible across all tool invocations
   - Path-based data structure manipulation (similar to Clojure's assoc-in/get-in)
   - Direct storage of JSON-compatible values without parsing
   - Path elements as arrays of strings and numbers
   - Tree visualization for debugging and inspection
   - Enables inter-agent communication and task tracking
   - No need for explicit namespace management or REPL state

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
   - Use `clojure_edit_*` tools for syntax-aware code editing
   - Always read a file with `read_file` before editing if it might have been modified externally
   - After using `file_write`, you can immediately edit the file without reading it first
   - Use `scratch_pad` for:
     - Tracking tasks and todos across tool invocations
     - Storing intermediate computation results
     - Building up complex data structures incrementally
     - Sharing context between different agents or tool calls

4. **Logging System**:
   - Uses `clojure.tools.logging` with Logback backend
   - Logs are written to `logs/clojure-mcp.log` with daily rotation
   - Configure log levels in `resources/logback.xml`
   - Server startup/shutdown and errors are logged automatically
   - Add logging in your code with:
     ```clojure
     (ns your.namespace
       (:require [clojure.tools.logging :as log]))
       
     (log/debug "Debug message")
     (log/info "Info message")
     (log/warn "Warning message")
     (log/error "Error message")
     (log/error exception "Error with exception")
     ```

5. **Project Maintenance**:
   - Run tests with `clojure -X:test`
   - Update this project summary after significant changes
   
6. **Testing Best Practices**:
   - Use the provided test utilities in `clojure-mcp.tools.test-utils`
   - Always use canonical paths (`.getCanonicalPath()`) when working with file operations
   - Register files with the timestamp tracker before attempting to modify them in tests
   - The `create-and-register-test-file` and `read-and-register-test-file` helpers handle this automatically
   - Include small delays between timestamp operations to ensure different timestamps
   - Key test files for reference:
     - `/test/clojure_mcp/tools/form_edit/tool_test.clj`: Main form editing tests with canonical path handling
     - `/test/clojure_mcp/tools/form_edit/sexp_replace_test.clj`: S-expression replacement tests
     - `/test/clojure_mcp/tools/test_utils.clj`: Shared test utilities

7. **Pattern-Based Code Exploration**:
   - Use the enhanced `read_file` tool for efficient codebase navigation
   - Combine `name_pattern` and `content_pattern` to focus on relevant code
   - Find specific defmethod implementations using their dispatch values
   - Examples:
     - Find all validation functions: `{:name_pattern "validate.*"}`
     - Find error handling: `{:content_pattern "try|catch|throw"}`
     - Find where a specific function is used: `{:content_pattern "some-important-function"}`
     - Find a specific defmethod: `{:name_pattern "area :rectangle"}`
     - Find defmethod with vector dispatch: `{:name_pattern "dispatch-with-vector \\[:feet :inches\\]"}`
   - Markdown-formatted output includes useful tips and pattern match information

## Pattern-Based Code Editing

The unified `clojure_edit` tool uses a pattern-matching approach for finding and editing Clojure code:

### Key Features

1. **Wildcard Patterns**: 
   - `_?` matches exactly one form (e.g., a symbol, list, vector)
   - `_*` matches zero or more forms

2. **Pattern Validation**:
   - Ensures patterns are valid S-expressions
   - Blocks comment matching (use `file_edit` for comments)
   - Prevents overly general patterns like lone wildcards
   - Validates that patterns match exactly one form in the file

3. **Error Handling**:
   - Provides detailed error messages with context
   - For duplicate matches, shows both matches to help refine patterns

### Usage Examples

```clojure
;; Match a specific function definition
(clojure_edit
  {:file_path "/path/to/file.clj",
   :sexp_pattern "(defn specific-function _*)",
   :raw_content "(defn specific-function [x] (println x) x)",
   :operation "replace"})

;; Match functions with a specific argument pattern
(clojure_edit
  {:file_path "/path/to/file.clj",
   :sexp_pattern "(defn _? [x y] _*)",
   :raw_content "(defn helper-function [x y] (+ x y))",
   :operation "insert_after"})

;; Match a specific multimethod implementation
(clojure_edit
  {:file_path "/path/to/file.clj",
   :sexp_pattern "(defmethod area :rectangle _*)",
   :raw_content "(defmethod area :rectangle [{:keys [width height]}] (* width height))",
   :operation "replace"})

;; Match a vector dispatch multimethod
(clojure_edit
  {:file_path "/path/to/file.clj",
   :sexp_pattern "(defmethod convert-units [:feet :inches] _*)",
   :raw_content "(defmethod convert-units [:feet :inches] [_ value] (* value 12))",
   :operation "replace"})
```

## Extension Points

1. **Adding New Tools**:
   - Create a new tool namespace in `/src/clojure_mcp/tools/` for active tools
   - Implement the required multimethods from `tool-system`
   - Register the tool in `main.clj` within the `my-tools` function
   - Note: Tools in `/src/clojure_mcp/other_tools/` are not automatically registered

2. **Enhancing Existing Tools**:
   - Most tools follow a pipeline architecture that can be modified by adding new steps
   - Example: We added a `check_for_duplicate_matches` step to the `unified_clojure_edit` pipeline
     to validate that patterns match exactly one form, preventing ambiguous edits
   - Pipeline steps follow a thread-first pattern with error short-circuiting

3. **Re-activating Unused Tools**:
   - Tools in `/src/clojure_mcp/other_tools/` can be re-activated by:
     - Moving them back to `/src/clojure_mcp/tools/`
     - Updating namespace declarations
     - Adding them to the imports and `my-tools` function in `main.clj`
   - Alternatively, create custom MCP servers using these tools via the core API

4. **Enhancing Prompt System**:
   - Add new prompts in `/resources/prompts/`
   - Register them in `prompts.clj`

5. **Improving Code Editing**:
   - Extend form editing capabilities in `tools/form_edit/core.clj`
   - Add specialized tools for common editing patterns
   - Extend pattern matching in `sexp/match.clj`

6. **Language Model Integration**:
   - Explore langchain4j integration for more advanced AI capabilities
   - Implement feedback mechanisms for model improvements

7. **IDE Integration**:
   - Extend `/src/clojure_mcp/utils/emacs_integration.clj` for better editor support
   - Add support for VS Code or other editors

## Recent Organizational Changes

**Scratch Pad Tool Addition (Latest)**: Added a new `scratch_pad` tool for persistent data storage across tool invocations. This tool enables:
- Inter-agent communication through shared state
- Task tracking with structured todo lists
- Building complex data structures incrementally
- Path-based data manipulation similar to Clojure's assoc-in/get-in
- Direct storage of JSON-compatible values (objects, arrays, strings, numbers, booleans, null)
- Path elements specified as arrays of strings and numbers

**Tool Reorganization**: To improve codebase maintainability, unused tools have been moved to `/src/clojure_mcp/other_tools/`. This separation clarifies which tools are actively used in the main MCP server (`main.clj`) versus those that remain available but are not currently essential. The moved tools include:

- `create_directory`, `list_directory`, `move_file`: Basic file system operations
- `namespace`, `symbol`: Advanced Clojure introspection tools

All moved tools retain full functionality with passing tests and can be easily re-activated if needed. This organizational approach helps maintain focus on the core tool set while preserving additional capabilities for specialized use cases.

This project summary is designed to provide AI assistants with a quick understanding of the Clojure MCP project structure and capabilities, enabling more effective assistance with minimal additional context. The project continues to evolve with improvements such as the unified clojure_edit tool with pattern-based matching and validation to ensure safer, more precise code editing operations while maintaining compatibility with a wide range of LLMs.
