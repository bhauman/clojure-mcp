#!/usr/bin/env python3
"""
HTTP/HTTPS Proxy Wrapper with Authentication

This script creates a local HTTP proxy that handles proxy authentication
and forwards requests to an upstream proxy that requires JWT authentication.

Usage:
    python3 proxy-wrapper.py [local_port]

The upstream proxy configuration is read from the http_proxy environment variable.
"""

import socket
import threading
import select
import base64
import os
import sys
import re
from urllib.parse import urlparse

class ProxyHandler:
    def __init__(self, upstream_proxy_url):
        self.upstream_proxy_url = upstream_proxy_url
        self.parse_upstream_proxy()

    def parse_upstream_proxy(self):
        """Parse the upstream proxy URL to extract host, port, and credentials."""
        # Format: http://username:password@host:port
        parsed = urlparse(self.upstream_proxy_url)
        self.upstream_host = parsed.hostname
        self.upstream_port = parsed.port or 80

        # Extract credentials from userinfo
        if parsed.username and parsed.password:
            auth_string = f"{parsed.username}:{parsed.password}"
            self.auth_header = "Basic " + base64.b64encode(auth_string.encode()).decode()
        else:
            self.auth_header = None

        print(f"[CONFIG] Upstream proxy: {self.upstream_host}:{self.upstream_port}")
        print(f"[CONFIG] Authentication: {'configured' if self.auth_header else 'not configured'}")

    def handle_client(self, client_socket, address):
        """Handle a client connection."""
        try:
            # Set timeout to prevent hanging on slow clients
            client_socket.settimeout(30)
            request = client_socket.recv(4096).decode('utf-8', errors='ignore')
            if not request:
                client_socket.close()
                return

            # Parse the request line
            first_line = request.split('\n')[0]
            print(f"[REQUEST] {address[0]}:{address[1]} -> {first_line.strip()}")

            # Check if this is a CONNECT request (for HTTPS)
            if first_line.startswith('CONNECT'):
                self.handle_connect(client_socket, request, first_line)
            else:
                # For regular HTTP requests
                self.handle_http(client_socket, request)

        except Exception as e:
            print(f"[ERROR] {e}")
            client_socket.close()

    def handle_connect(self, client_socket, request, first_line):
        """Handle CONNECT requests for HTTPS tunneling."""
        # Extract target host:port from CONNECT line
        parts = first_line.split()
        if len(parts) < 2:
            client_socket.close()
            return

        target = parts[1]

        # Connect to upstream proxy
        upstream_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        upstream_socket.connect((self.upstream_host, self.upstream_port))

        # Forward CONNECT request to upstream proxy with authentication
        connect_request = first_line + "\r\n"

        # Add Proxy-Authorization header if we have credentials
        if self.auth_header:
            connect_request += f"Proxy-Authorization: {self.auth_header}\r\n"

        connect_request += "\r\n"

        upstream_socket.sendall(connect_request.encode())

        # Get response from upstream proxy
        response = upstream_socket.recv(4096)
        response_str = response.decode('utf-8', errors='ignore')

        # Check if connection was successful
        if "200" in response_str.split('\n')[0]:
            # Send success response to client
            client_socket.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")

            # Now relay data between client and upstream proxy
            self.relay_data(client_socket, upstream_socket)
        else:
            # Forward the error response to client
            print(f"[ERROR] Upstream proxy responded: {response_str.split(chr(10))[0]}")
            client_socket.sendall(response)
            client_socket.close()
            upstream_socket.close()

    def handle_http(self, client_socket, request):
        """Handle regular HTTP requests."""
        # Connect to upstream proxy
        upstream_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        upstream_socket.connect((self.upstream_host, self.upstream_port))

        # Add Proxy-Authorization header if not present and we have credentials
        if self.auth_header and "Proxy-Authorization:" not in request:
            # Insert the header after the first line
            lines = request.split('\r\n')
            lines.insert(1, f"Proxy-Authorization: {self.auth_header}")
            request = '\r\n'.join(lines)

        # Forward request to upstream proxy
        upstream_socket.sendall(request.encode())

        # Relay response back to client
        self.relay_data(client_socket, upstream_socket)

    def relay_data(self, client_socket, upstream_socket):
        """Relay data bidirectionally between client and upstream proxy."""
        sockets = [client_socket, upstream_socket]

        while True:
            try:
                readable, _, exceptional = select.select(sockets, [], sockets, 60)

                if exceptional:
                    break

                for sock in readable:
                    data = sock.recv(8192)
                    if not data:
                        client_socket.close()
                        upstream_socket.close()
                        return

                    # Send to the other socket
                    if sock is client_socket:
                        upstream_socket.sendall(data)
                    else:
                        client_socket.sendall(data)

            except Exception as e:
                break

        try:
            client_socket.close()
            upstream_socket.close()
        except:
            pass


def start_proxy(local_port, upstream_proxy_url):
    """Start the local proxy server."""
    handler = ProxyHandler(upstream_proxy_url)

    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_socket.bind(('127.0.0.1', local_port))
    server_socket.listen(100)

    print(f"[INFO] Proxy server listening on 127.0.0.1:{local_port}")
    print(f"[INFO] Forwarding to: {handler.upstream_host}:{handler.upstream_port}")
    print(f"[INFO] Configure your application to use: http://127.0.0.1:{local_port}")
    print()

    try:
        while True:
            client_socket, address = server_socket.accept()
            thread = threading.Thread(target=handler.handle_client, args=(client_socket, address))
            thread.daemon = True
            thread.start()
    except KeyboardInterrupt:
        print("\n[INFO] Shutting down proxy server...")
        server_socket.close()


if __name__ == "__main__":
    # Get upstream proxy from environment
    upstream_proxy_url = os.environ.get('http_proxy') or os.environ.get('HTTP_PROXY')

    if not upstream_proxy_url:
        print("ERROR: No http_proxy environment variable found!")
        sys.exit(1)

    # Get local port from command line or use default
    local_port = int(sys.argv[1]) if len(sys.argv) > 1 else 8888

    print("=" * 60)
    print("HTTP/HTTPS Proxy Wrapper with Authentication")
    print("=" * 60)
    print()

    start_proxy(local_port, upstream_proxy_url)
