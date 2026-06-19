# Clojure MCP Prompt CLI

A command-line interface for interacting with an AI agent that has access to all Clojure MCP tools.

## Prerequisites

- A running nREPL server (default port 7888, configurable)
- Configured API keys for the chosen model (Anthropic, OpenAI, etc.)

## Usage

Start your nREPL server:
```bash
clojure -M:nrepl
```

In another terminal, run the CLI:
```bash
clojure -M:prompt-cli -p "Your prompt here"
```

## Options

- `-p, --prompt PROMPT` - The prompt to send to the agent (required)
- `-r, --resume` - Resume the most recent session with its conversation history
- `-m, --model MODEL` - Override the default model (e.g., `:openai/gpt-4`, `:anthropic/claude-3-5-sonnet`)
- `-c, --config CONFIG` - Path to a custom agent configuration file (optional)
- `-d, --dir DIRECTORY` - Working directory (defaults to REPL's working directory)
- `-P, --port PORT` - nREPL server port (default: 7888)
- `-h, --help` - Show help message

## Examples

Basic usage with default model:
```bash
clojure -M:prompt-cli -p "What namespaces are available?"
```

Use a specific model:
```bash
clojure -M:prompt-cli -p "Evaluate (+ 1 2)" -m :openai/gpt-4
```

Create code:
```bash
clojure -M:prompt-cli -p "Create a fibonacci function"
```

Use a custom agent configuration:
```bash
clojure -M:prompt-cli -p "Analyze this project" -c my-custom-agent.edn
```

Connect to a different nREPL port:
```bash
clojure -M:prompt-cli -p "Run tests" -P 8888
```

Specify a working directory:
```bash
clojure -M:prompt-cli -p "List files" -d /path/to/project
```

Resume the most recent session:
```bash
clojure -M:prompt-cli --resume -p "Continue with the next step"
```

Resume with a different model:
```bash
clojure -M:prompt-cli --resume -p "Now refactor the code" -m :openai/gpt-4
```

## Session Persistence

Sessions are automatically saved after each prompt execution:
- Sessions are stored in `.clojure-mcp/prompt-cli-sessions/` within your working directory
- Each session file is timestamped (e.g., `2025-11-06T14-30-45.json`)
- Sessions contain the full conversation history and model information
- Use `--resume` to continue from the most recent session
- When resuming, the conversation history is displayed before processing your new prompt
- You can resume with a different model using `-m` to override the original session's model

## Configuration

The CLI properly initializes the nREPL connection with:
- Automatic detection of the working directory from the REPL
- Loading of `.clojure-mcp/config.edn` from the working directory
- Environment detection and initialization (Clojure, ClojureScript, etc.)
- Loading of REPL helper functions

## Default Agent Configuration

By default, the CLI uses the `parent-agent-config` which includes:
- The Clojure REPL system prompt
- Access to all available tools
- Project context (summary and project structure)
- Stateless memory (each invocation is independent)

## Custom Agent Configuration

You can create a custom agent configuration file in EDN format:

```clojure
{:id :my-agent
 :name "my_agent"
 :description "My custom agent"
 :system-message "Your system prompt here..."
 :context true  ; Include project context
 :enable-tools [:read_file :clojure_eval :grep]  ; Specific tools or [:all]
 :memory-size 100  ; Or false for stateless
 :model :anthropic/claude-3-5-sonnet-20241022}
```

## Environment Variables

Set `DEBUG=1` to see stack traces on errors:
```bash
DEBUG=1 clojure -M:prompt-cli -p "Your prompt"
```

## Model Configuration

Models can be configured in `.clojure-mcp/config.edn`:
```clojure
{:models {:openai/my-gpt4 {:model-name "gpt-4"
                            :temperature 0.3
                            :api-key [:env "OPENAI_API_KEY"]}}}
```

Then use with:
```bash
clojure -M:prompt-cli -p "Your prompt" -m :openai/my-gpt4
```

## Tool Configuration

The agent has access to all tools by default, which are filtered based on the project's `.clojure-mcp/config.edn` settings:
- `enable-tools` and `disable-tools` settings are respected
- Tool-specific configurations from `tools-config` are applied
