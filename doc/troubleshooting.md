# Troubleshooting ClojureMCP

This guide covers common issues when setting up ClojureMCP, particularly with Claude Desktop.

## Quick Checklist

If Claude Desktop can't run the `clojure` command:

1. **Test your command manually**: Run the exact command from your config in a terminal
2. **Check your PATH**: Ensure `which clojure` works in a fresh terminal
3. **Enable logging**: Check Claude Desktop logs for error messages
4. **Simplify first**: Start with a basic configuration, then add complexity

If you continue to have issues, consider consulting with AI assistants (Claude, ChatGPT, Gemini) about the specific PATH configuration for your system setup.

## PATH Issues (Most Common)

If your `claude_desktop_config.json` doesn't work, it's most likely that the `PATH` environment variable is not set up correctly to find `clojure` and `java`.

You can fix this by explicitly setting the `PATH` environment variable:

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/opt/homebrew/bin/bash",
            "args": [
                "-c",
                "export PATH=/opt/homebrew/bin:$PATH; exec clojure -Tmcp start :not-cwd true :port 7888"
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

## Debugging Strategies

These are some examples to give you a way to debug a failed ClojureMCP startup.

### Examine the environment

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/opt/homebrew/bin/bash",
            "args": [
                "-c",
                "echo $PATH > /Users/yourname/claude-desktop-path.txt"
            ]
        }
    }
}
```

### Capture ClojureMCP output

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/opt/homebrew/bin/bash",
            "args": [
                "-c",
                "clojure -Tmcp start :not-cwd true :port 7888 | tee /Users/yourname/clojure-mcp-stdout.log"
            ]
        }
    }
}
```

## Advanced Configuration Example

If you need to source environment variables (like API keys, see [LLM API Keys](../README.md#llm-api-keys)):

```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "/bin/sh",
            "args": [
                "-c",
                "source ~/.my-llm-api-keys.sh && PATH=/Users/username/.nix-profile/bin:$PATH && clojure -Tmcp start :not-cwd true :port 7888"
            ]
        }
    }
}
```

## Connection Refused Error

```
Execution error (ConnectException) at sun.nio.ch.Net/connect0 (Net.java:-2).
Connection refused
```

This means ClojureMCP couldn't connect to your nREPL server. Ensure:
- The nREPL server is running
- The port numbers match (default: 7888)

## Extraneous Output

If you see output other than JSON-RPC messages, it's likely due to ClojureMCP being included in a larger environment. Ensure ClojureMCP runs with its own isolated dependencies.

## Claude Desktop Logs

Claude Desktop logs can be found at:
- **macOS**: `~/Library/Logs/Claude/`
- **Windows**: `%APPDATA%\Claude\logs\`

Check these logs for detailed error messages when ClojureMCP fails to start.
