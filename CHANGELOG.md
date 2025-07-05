# Changelog

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
