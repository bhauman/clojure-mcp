#!/bin/bash

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

# Create logs directory if it doesn't exist
mkdir -p "$SCRIPT_DIR/logs"

cd "$SCRIPT_DIR"

# Generate timestamp for log files
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
STDIN_LOG="$SCRIPT_DIR/logs/mcp_stdin.log"
STDOUT_LOG="$SCRIPT_DIR/logs/mcp_stdout.log"

source  ~/.current_api_keys

# Create a named pipe for stdin capture
PIPE=$(mktemp -u)
mkfifo "$PIPE"

PORT=7888
# PORT=44264
# PORT=58709 # conj-talk

# Start tee process to capture stdin in background
tee "$STDIN_LOG" < "$PIPE" | clojure -X:dev-mcp :not-cwd true :enable-logging? true :port $PORT 2>&1 | tee "$STDOUT_LOG" &
# tee "$STDIN_LOG" < "$PIPE" | clojure -X:mcp-shadow :enable-logging? true :port $PORT 2>&1 | tee "$STDOUT_LOG" &
# tee "$STDIN_LOG" < "$PIPE" | clojure -X:mcp-shadow-dual :enable-logging? true :port 7888 :shadow-port $PORT 2>&1 | tee "$STDOUT_LOG" &

# Get the PID of the background pipeline
CLOJURE_PID=$!

# Redirect stdin to the pipe
cat > "$PIPE"

# Clean up
rm "$PIPE"
wait $CLOJURE_PID
