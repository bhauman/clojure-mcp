# Docker Usage with Clojure MCP

This document explains how to use Clojure MCP when your nREPL server runs in a Docker container but the MCP server runs on the host.

## Overview

When running nREPL in Docker, the container reports its working directory as `/app` (or similar), while the MCP server running on the host needs to work with host filesystem paths. Clojure MCP supports a `:user-dir-override` parameter that allows you to specify the host filesystem path while connecting to a Docker-based nREPL.

This enables a flexible development setup where:

- Your Clojure environment runs in a consistent Docker container
- The MCP server runs on the host with access to your editor/IDE
- File operations work seamlessly between both environments

## Setup Examples

### Basic Docker Setup

1. **Start your nREPL in Docker**:

```bash
# In your project directory
docker run -v $(pwd):/app -p 7888:7888 clojure:openjdk-11-tools-deps \
  clojure -M:dkr-nrepl
```

2. **Start the MCP server on host** with directory override:

**From Terminal:**
```bash
# Using the override parameter
clojure -X:mcp :port 7888 :user-dir-override "$(pwd)"
````

**From Cursor:**

`<project_root>/.cursor/mcp.json` (note that `"."` expands to the project directory)
```json
{
    "mcpServers": {
        "clojure-mcp": {
            "command": "sh",
            "args": [
                "<project_root>/mcp-docker.sh",
                "7888",
                "."
            ]
        }
    }
}
```

`<project_root>/mcp-docker.sh`

```bash
#!/bin/bash

PORT=${1:-7888}
PATH_OVERRIDE=${2:-$(pwd)}
exec clojure -X:mcp :port "$PORT" :user-dir-override "\"$PATH_OVERRIDE\""
```

### Docker Compose Setup

Create a `docker-compose.yml`:

```yaml
version: '3.8'
services:
  clojure-nrepl:
    image: clojure:openjdk-11-tools-deps
    working_dir: /app
    volumes:
      - .:/app
    ports:
      - "7888:7888"
    command:
      - "clojure"
      - "-M:dkr-nrepl"
      - "--bind"
      - "0.0.0.0"
      - "--port"
      - "7888"
```

Then start services:

```bash
# Start Docker services
docker-compose up -d
```


## How It Works

- **Docker nREPL**: Reports working directory as `/app` and handles code evaluation
- **Host MCP Server**: Uses the override path (e.g., `/Users/you/project`) for file operations
- **File Synchronization**: Docker volume mount ensures both environments see the same files
- **Seamless Integration**: MCP server automatically uses the correct paths for the host filesystem

## Troubleshooting

### Common Issues

1. **Path doesn't exist**: Ensure the override path exists on the host filesystem
2. **Permission issues**: Verify the MCP server has read/write access to the override directory
3. **Volume mounting**: Confirm Docker volume mounts are configured correctly so both environments access the same files

