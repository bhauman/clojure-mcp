#!/bin/bash
# Setup script for running Clojure in Claude Code's authenticated proxy environment
#
# This script configures the environment to work around Java's limitation
# where it cannot send authentication headers during HTTPS CONNECT requests.
#
# Usage:
#   source setup-claude-code-env.sh

set -e

PROXY_PORT="${PROXY_PORT:-8888}"
PROXY_LOG="/tmp/proxy.log"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "============================================================"
echo "Clojure Development Setup for Claude Code"
echo "============================================================"
echo ""

# Check if we're in Claude Code environment
if [ -z "$http_proxy" ] && [ -z "$HTTP_PROXY" ]; then
    echo "[WARN] No http_proxy environment variable detected"
    echo "  This setup is designed for Claude Code's authenticated proxy environment"
    echo ""
fi

# Start proxy wrapper if not already running
if pgrep -f "proxy-wrapper.py.*$PROXY_PORT" > /dev/null; then
    PROXY_PID=$(pgrep -f "proxy-wrapper.py.*$PROXY_PORT")
    echo "[OK] Proxy wrapper already running on port $PROXY_PORT (PID: $PROXY_PID)"
else
    echo "Starting proxy wrapper on port $PROXY_PORT..."
    if [ -f "$SCRIPT_DIR/proxy-wrapper.py" ]; then
        python3 "$SCRIPT_DIR/proxy-wrapper.py" $PROXY_PORT > $PROXY_LOG 2>&1 &
        sleep 2

        if pgrep -f "proxy-wrapper.py.*$PROXY_PORT" > /dev/null; then
            PROXY_PID=$(pgrep -f "proxy-wrapper.py.*$PROXY_PORT")
            echo "[OK] Proxy wrapper started (PID: $PROXY_PID)"
        else
            echo "[ERROR] Failed to start proxy wrapper"
            echo "  Check logs: tail $PROXY_LOG"
            return 1
        fi
    else
        echo "[ERROR] proxy-wrapper.py not found in $SCRIPT_DIR"
        return 1
    fi
fi
echo "  Logs: tail -f $PROXY_LOG"
echo ""

# Configure Maven settings
MAVEN_SETTINGS="$HOME/.m2/settings.xml"
mkdir -p "$HOME/.m2"

if [ ! -f "$MAVEN_SETTINGS" ] || ! grep -q "127.0.0.1" "$MAVEN_SETTINGS"; then
    echo "Configuring Maven settings for proxy..."
    cat > "$MAVEN_SETTINGS" <<EOF
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
      <port>$PROXY_PORT</port>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
    <proxy>
      <id>local-proxy-https</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>127.0.0.1</host>
      <port>$PROXY_PORT</port>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
EOF
    echo "[OK] Created $MAVEN_SETTINGS"
else
    echo "[OK] Maven settings already configured"
fi
echo ""

# Export Java system properties
echo "Configuring Java proxy settings..."
export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=$PROXY_PORT -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=$PROXY_PORT -Dmaven.artifact.threads=2"
echo "[OK] JAVA_TOOL_OPTIONS set"
echo ""

# Configure Gradle (optional but recommended)
GRADLE_PROPS="$HOME/.gradle/gradle.properties"
mkdir -p "$HOME/.gradle"

if [ ! -f "$GRADLE_PROPS" ] || ! grep -q "127.0.0.1" "$GRADLE_PROPS"; then
    echo "Configuring Gradle proxy settings..."
    cat > "$GRADLE_PROPS" <<EOF
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=$PROXY_PORT
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=$PROXY_PORT
systemProp.http.nonProxyHosts=localhost|127.0.0.1
EOF
    echo "[OK] Created $GRADLE_PROPS"
else
    echo "[OK] Gradle settings already configured"
fi
echo ""

echo "============================================================"
echo "Setup Complete!"
echo "============================================================"
echo ""
echo "You can now use Clojure CLI:"
echo ""
echo "  # Start a REPL"
echo "  clojure -M:nrepl"
echo ""
echo "  # Run MCP server"
echo "  clojure -X:mcp"
echo ""
echo "  # Run tests"
echo "  clojure -X:test"
echo ""
echo "To check proxy activity:"
echo "  tail -f $PROXY_LOG"
echo ""
echo "============================================================"
