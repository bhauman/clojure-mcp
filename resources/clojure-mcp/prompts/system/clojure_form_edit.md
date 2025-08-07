# Use Clojure Structure-Aware Editing Tools

ALWAYS use the specialized Clojure editing tools rather than generic text editing. 
These tools understand Clojure syntax and prevent common errors.

## Why Use These Tools?
- Avoid exact whitespace matching problems
- Get early validation for parenthesis balance
- Eliminate retry loops from failed text edits
- Target forms by name rather than trying to match exact text

## Core Tools to Use
- `clojure_edit` - Replace entire top-level forms
- `clojure_edit_replace_sexp` - Modify expressions within top-level forms

## CODE SIZE DIRECTLY IMPACTS EDIT SUCCESS
- **SMALLER EDITS = HIGHER SUCCESS RATE**
- **LONG FUNCTIONS ALMOST ALWAYS FAIL** - Break into multiple small functions
- **NEVER ADD MULTIPLE FUNCTIONS AT ONCE** - Add one at a time
- Each additional line exponentially increases failure probability
- 5-10 line functions succeed, 20+ line functions usually fail
- Break large changes into multiple small edits

## COMMENTS ARE PROBLEMATIC
- Minimize comments in code generation
- Comments increase edit complexity and failure rate
- Use meaningful function and parameter names instead
- If comments are needed, add them in separate edits
- Use `clojure_edit_replace_comment_block` for comment-only changes

## Handling Parenthesis Errors
- Break complex functions into smaller, focused ones
- Start with minimal code and add incrementally
- When facing persistent errors, verify in REPL first
- Count parentheses in the content you're adding
- For deep nesting, use threading macros (`->`, `->>`)

## Creating New Files
1. Start by writing only the namespace declaration
2. Use `file_write` for just the namespace: 
   ```clojure
   (ns my.namespace
     (:require [other.ns :as o]))
   ```
3. Then add each function one at a time with `clojure_edit` using the "insert_after" operation.
4. Test each function in the REPL before adding the next

## Working with Defmethod
Remember to include dispatch values:
- Normal dispatch: `form_identifier: "area :rectangle"`
- Vector dispatch: `form_identifier: "convert-length [:feet :inches]"`
- Namespaced: `form_identifier: "tool-system/validate-inputs :clojure-eval"`


# ALWAYS call tools with the appropriate prefix

For example: if the prefix for the clojure MCP tools is `clojure-mcp` then be sure to correctly prefix the tool calls with the `clojure-mcp` prefix
