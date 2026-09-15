#!/usr/bin/env python3
"""
A stand-in for Heylana's proxy, for testing the app without spending anything.

It answers the same three routes with fixed replies: a canned answer, a second
of tone instead of a voice, and a made-up listening key. Nothing here talks to
Anthropic, Cartesia or Deepgram — that is the whole point.

    ./scripts/stub-proxy.py                 # serves on 127.0.0.1:8787
    adb reverse tcp:8787 tcp:8787           # the phone can now reach it

For the listening socket, add to local.properties and rebuild:
    heylana.listenUrl=ws://127.0.0.1:8787/v1/listen
and run the stub with --refuse-listen to make it refuse, as a bad key would.

Then set the address in the app: Settings -> Advanced -> Use this address
instead -> http://127.0.0.1:8787
"""

import base64
import hashlib
import json
import math
import re
import struct
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
PORT = int(ARGS[0]) if ARGS else 8787

# The listening socket, standing in for Deepgram:
#   default          accept it, take the audio, answer CloseStream with no words
#   --refuse-listen  refuse the upgrade with 401 and a JSON body, as a bad key would
REFUSE_LISTEN = "--refuse-listen" in sys.argv

# Plans and payments, standing in for the worker. Nothing here verifies a
# signature or reads a chain: it only lets the app's screens be exercised.
#   --talks-cap   /chat answers 429 talks_cap, as a used-up free month would
TALKS_CAP = "--talks-cap" in sys.argv
#   --devnet      /me names devnet, so the app asks Seed Vault about devnet and greys out SKR
DEVNET = "--devnet" in sys.argv
STUB_JUDGE_CODE = "stub-judge"
STATE = {"plan": "free", "used": 0, "bonus": 20, "wallet": None, "pro_until": None, "judge_until": None}


def standing():
    plan = STATE["plan"]
    return {
        "plan": plan,
        "used": STATE["used"],
        "limit": None if plan != "free" else 30 + (STATE["bonus"] if STATE["wallet"] else 0),
        "skills_cap": 3 if plan == "free" else 10,
        "pro_until": STATE["pro_until"],
        "judge_until": STATE["judge_until"],
        "resets_at": "2026-10-01T00:00:00.000Z",
        "wallet": STATE["wallet"],
        "cluster": "devnet" if DEVNET else "mainnet-beta",
    }
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# What the stub says back. Matches the JSON contract the app expects.
ANSWER = {
    "say": "The search bar is at the top of the screen.",
    "point_at": None,
    "task": None,
}

SAMPLE_RATE = 24000
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
    # Needed for the 101 upgrade; every other reply already sends content-length.
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path.strip("/") == "me":
            return self.send_json(200, standing())
        if not self.path.startswith("/v1/listen"):
            return self.send_json(404, {"reason": "unknown_route"})

        if REFUSE_LISTEN:
            sys.stderr.write("  listen: refusing the upgrade on purpose\n")
            return self.send_json(401, {
                "err_code": "INVALID_AUTH",
                "err_msg": "stub: refusing the listening socket on purpose",
            })

        key = self.headers.get("Sec-WebSocket-Key", "")
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.wfile.flush()
        sys.stderr.write("  listen: accepted\n")

        audio = 0
        while True:
            frame = self.read_frame()
            if frame is None:
                break
            opcode, payload = frame
            if opcode == 2:
                audio += len(payload)
            elif opcode == 1 and b"CloseStream" in payload:
                sys.stderr.write(f"  listen: CloseStream after {audio} bytes of audio\n")
                empty = {"type": "Results", "is_final": True,
                         "channel": {"alternatives": [{"transcript": ""}]}}
                self.write_frame(1, json.dumps(empty).encode())
                self.write_frame(8, struct.pack(">H", 1000))
                break
            elif opcode == 8:
                self.write_frame(8, payload[:2] or struct.pack(">H", 1000))
                break
        self.close_connection = True

    def read_frame(self):
        head = self.rfile.read(2)
        if len(head) < 2:
            return None
        opcode = head[0] & 0x0F
        masked = head[1] & 0x80
        length = head[1] & 0x7F
        if length == 126:
            length = struct.unpack(">H", self.rfile.read(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self.rfile.read(8))[0]
        mask = self.rfile.read(4) if masked else b"\0\0\0\0"
        data = bytearray(self.rfile.read(length))
        for i in range(len(data)):
            data[i] ^= mask[i % 4]
        return opcode, bytes(data)

    def write_frame(self, opcode, payload):
        header = bytes([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header += bytes([n])
        elif n < 65536:
            header += bytes([126]) + struct.pack(">H", n)
        else:
            header += bytes([127]) + struct.pack(">Q", n)
        self.wfile.write(header + payload)
        self.wfile.flush()

    def log_message(self, fmt, *args):
        device = self.headers.get("X-Heylana-Device", "")[:8]
        sys.stderr.write(f"{self.path} device={device} {fmt % args}\n")

    def do_POST(self):
        length = int(self.headers.get("content-length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        body = json.loads(raw or b"{}")

        if not self.headers.get("X-Heylana-Device"):
            return self.send_json(400, {"reason": "no_device"})

        route = self.path.strip("/")

        if route == "wallet/challenge":
            pubkey = body.get("pubkey", "")
            return self.send_json(200, {"nonce": "stubnonce", "message": f"Heylana stub sign-in for {pubkey}"})

        if route == "wallet/verify":
            first = STATE["wallet"] is None
            STATE["wallet"] = body.get("pubkey")
            return self.send_json(200, {"session": "stub.session", "pubkey": STATE["wallet"],
                                        "welcome_granted": first, "me": standing()})

        if route == "judge":
            if body.get("code") != STUB_JUDGE_CODE:
                return self.send_json(403, {"reason": "bad_code"})
            STATE["plan"], STATE["judge_until"] = "judge", "2026-11-09T23:59:59.000Z"
            return self.send_json(200, standing())

        if route == "pay/quote":
            usdc = body.get("currency") == "usdc"
            return self.send_json(200, {
                "currency": body.get("currency"),
                # Placeholders only: never approve a payment against the stub.
                "mint": "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                "amount": "100000" if usdc else "3333334", "decimals": 6,
                "token_program": "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                "treasury": "11111111111111111111111111111111",
                "reference": "4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T",
                "expires_at": "2099-01-01T00:00:00.000Z", "price_usd": "0.10"})

        if route == "pay/blockhash":
            cluster = "devnet" if DEVNET else "mainnet-beta"
            if body.get("cluster", cluster) != cluster:
                return self.send_json(409, {"reason": "wrong_cluster"})
            return self.send_json(200, {"blockhash": "EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k",
                                        "last_valid_block_height": 1, "cluster": cluster})

        if route == "pay/confirm":
            STATE["plan"], STATE["pro_until"] = "pro", "2026-10-15T12:00:00.000Z"
            return self.send_json(200, standing())

        if route == "chat" and TALKS_CAP:
            return self.send_json(429, {"reason": "talks_cap", "plan": "free", "used": 30, "limit": 30,
                                        "resets_at": "2026-10-01T00:00:00.000Z"})

        if self.path.strip("/") == "chat":
            STATE["used"] += 1
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
    mode = "refusing" if REFUSE_LISTEN else "accepting"
    print(f"stub proxy on http://127.0.0.1:{PORT} — chat, tts, stt-token, listen ({mode}), "
          f"wallet, me, judge (code: {STUB_JUDGE_CODE}), pay{' — talks capped' if TALKS_CAP else ''}")
    print("run: adb reverse tcp:%d tcp:%d" % (PORT, PORT))
    ThreadingHTTPServer(("127.0.0.1", PORT), Stub).serve_forever()
