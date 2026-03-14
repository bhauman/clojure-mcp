# Clojure MCP Development Guide

## Build Commands
- Run REPL with MCP server: `clojure -X:mcp` (starts on port 7888)
- Run all tests: `clojure -M:test`
- Run linter: `clj-kondo --lint src` or `clj-kondo --lint src test` for both

## Code Style Guidelines
- **Imports**: Use `:require` with ns aliases (e.g., `[clojure.string :as string]`)
- **Naming**: Use kebab-case for vars/functions; end predicates with `?` (e.g., `is-top-level-form?`)
- **Error handling**: Use `try/catch` with specific exception handling; atom for tracking errors
- **Formatting**: 2-space indentation; maintain whitespace in edited forms
- **Namespaces**: Align with directory structure (`clojure-mcp.repl-tools`)
- **Testing**: Use `deftest` with descriptive names; `testing` for subsections; `is` for assertions
- **REPL Development**: Prefer REPL-driven development for rapid iteration and feedback

## MCP Tool Guidelines
- Include clear tool `:description` for LLM guidance
- Validate inputs and provide helpful error messages
- Return structured data with both result and error status
- Maintain atom-based state for consistent service access
- **No backwards compatibility required**: This is an MCP server — tool schemas, parameters, and behavior can change freely between versions. Don't hesitate to make breaking changes to tool contracts when it improves the design.

Fast Apply: IMPORTANT: Use \`edit_file\` over \`str_replace\` or full file writes. It works with partial code snippets—no need for full file content.
