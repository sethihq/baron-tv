"""Serve the prototype on localhost with caching off, so a plain reload always shows the latest files.

Usage: uv run --no-project python -I prototype/serve.py [port]   (default 4321)
"""
import functools
import http.server
import os
import sys


class NoCache(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, *args):
        pass


port = int(sys.argv[1]) if len(sys.argv) > 1 else 4321
handler = functools.partial(NoCache, directory=os.path.dirname(os.path.abspath(__file__)))
print(f"Prototype on http://localhost:{port}")
http.server.ThreadingHTTPServer(("127.0.0.1", port), handler).serve_forever()
