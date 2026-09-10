#!/usr/bin/env python3
"""
A stand-in for Heylana's proxy, for testing the app without spending anything.

It answers the same three routes with fixed replies: a canned answer, a second
of tone instead of a voice, and a made-up listening key. Nothing here talks to
Anthropic, Cartesia or Deepgram — that is the whole point.

    ./scripts/stub-proxy.py                 # serves on 127.0.0.1:8787
    adb reverse tcp:8787 tcp:8787           # the phone can now reach it

Then set the address in the app: Settings -> Advanced -> Use this address
instead -> http://127.0.0.1:8787
"""

import json
import math
import re
import struct
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8787

# What the stub says back. Matches the JSON contract the app expects.
ANSWER = {
    "say": "The search bar is at the top of the screen.",
    "point_at": None,
    "task": None,
}

SAMPLE_RATE = 22050
TONE_SECONDS = 1.2
TONE_HZ = 220.0


def tone() -> bytes:
    """A second of quiet sine, as raw 16-bit audio: what /tts streams."""
    frames = int(SAMPLE_RATE * TONE_SECONDS)
    out = bytearray()
    for i in range(frames):
        # Fade in and out so it does not click.
        envelope = min(1.0, i / 2000.0, (frames - i) / 2000.0)
        value = int(6000 * envelope * math.sin(2 * math.pi * TONE_HZ * i / SAMPLE_RATE))
        out += struct.pack("<h", value)
    return bytes(out)


TONE = tone()


class Stub(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        device = self.headers.get("X-Heylana-Device", "")[:8]
        sys.stderr.write(f"{self.path} device={device} {fmt % args}\n")

    def do_POST(self):
        length = int(self.headers.get("content-length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        body = json.loads(raw or b"{}")

        if not self.headers.get("X-Heylana-Device"):
            return self.send_json(400, {"reason": "no_device"})

        if self.path.strip("/") == "chat":
            mode = body.get("mode")
            if mode not in ("quick", "task"):
                return self.send_json(400, {"reason": "bad_mode"})
            return self.send_json(200, {
                "content": [{"type": "text", "text": json.dumps(ANSWER)}],
                "usage": {"input_tokens": 812, "output_tokens": 37},
            })

        if self.path.strip("/") == "tts":
            text = str(body.get("text", ""))[:400]
            sys.stderr.write(f"  tts chars={len(text)} voice={body.get('voice')}\n")
            self.send_response(200)
            self.send_header("content-type", "audio/L16")
            self.send_header("x-sample-rate", str(SAMPLE_RATE))
            self.send_header("content-length", str(len(TONE)))
            self.end_headers()
            self.wfile.write(TONE)
            return

        if self.path.strip("/") == "stt-token":
            return self.send_json(200, {"key": "stub-listening-key", "expires_in": 120})

        return self.send_json(404, {"reason": "unknown_route"})

    def send_json(self, status, payload):
        raw = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


if __name__ == "__main__":
    print(f"stub proxy on http://127.0.0.1:{PORT} — chat, tts, stt-token")
    print("run: adb reverse tcp:%d tcp:%d" % (PORT, PORT))
    HTTPServer(("127.0.0.1", PORT), Stub).serve_forever()
