# Claude Code Environment Setup Guide

This guide explains how to set up the Clojure MCP project in Claude Code's authenticated proxy environment.

## The Problem

Claude Code uses an authenticated proxy for all network requests. However, Java applications (including Clojure CLI) cannot send authentication headers during HTTPS CONNECT requests, which prevents them from accessing Maven repositories through the proxy.

## The Solution

We use a local proxy wrapper that:
1. Listens on localhost (port 8888 by default)
2. Accepts requests from Java/Clojure
3. Adds authentication headers
4. Forwards requests to Claude Code's authenticated proxy

## Quick Setup

1. **Run the setup script:**
   ```bash
   source claude-code-setup/setup-claude-code-env.sh
   ```

   This will:
   - Start the proxy wrapper on port 8888
   - Configure Maven settings (`~/.m2/settings.xml`)
   - Configure Gradle settings (`~/.gradle/gradle.properties`)
   - Export `JAVA_TOOL_OPTIONS` with proxy configuration

2. **Verify the setup:**
   ```bash
   clojure -M -e '(println "Success!")'
   ```

3. **Run the MCP server:**
   ```bash
   clojure -X:mcp
   ```

## Files

- **`claude-code-setup/proxy-wrapper.py`** - Python script that acts as a local proxy wrapper
- **`claude-code-setup/setup-claude-code-env.sh`** - Setup script to configure the environment
- **`CLAUDE_CODE_WEB_SETUP.md`** - This documentation file

## Manual Setup (if needed)

If you need to manually configure the environment:

### 1. Start the Proxy Wrapper

```bash
python3 claude-code-setup/proxy-wrapper.py 8888 > /tmp/proxy.log 2>&1 &
```

### 2. Configure Maven

Create `~/.m2/settings.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              http://maven.apache.org/xsd/settings-1.0.0.xsd">
  <proxies>
    <proxy>
      <id>local-proxy-http</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>127.0.0.1</host>
      <port>8888</port>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
    <proxy>
      <id>local-proxy-https</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>127.0.0.1</host>
      <port>8888</port>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
```

### 3. Set Java System Properties

```bash
export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=8888 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=8888 -Dmaven.artifact.threads=2"
```

Note: We limit `maven.artifact.threads` to 2 to avoid overwhelming the proxy with too many parallel connections.

## Troubleshooting

### Check if proxy wrapper is running

```bash
pgrep -f proxy-wrapper.py
```

### Check proxy logs

```bash
tail -f /tmp/proxy.log
```

### Test network connectivity

```bash
# Test direct access (should work via Claude Code proxy)
curl -I https://repo1.maven.org/maven2/

# Test through local proxy
curl -x http://127.0.0.1:8888 -I https://repo1.maven.org/maven2/
```

### Restart the proxy wrapper

```bash
pkill -f proxy-wrapper.py
python3 claude-code-setup/proxy-wrapper.py 8888 > /tmp/proxy.log 2>&1 &
```

### Use a different port

```bash
PROXY_PORT=9999 source claude-code-setup/setup-claude-code-env.sh
```

## Credits

This setup is based on the approach documented in:
https://github.com/michaelwhitford/claude-code-explore

The proxy wrapper solution addresses Java's limitation with authenticated proxies in the Claude Code runtime environment.
