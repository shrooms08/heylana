#!/usr/bin/env python3
"""
Proves the first-time line end to end against the deployed worker, spending no talks.

It signs in as the eval's own throwaway wallet (scripts/eval/.state.json — never the
phone's), turns memory on, prepares a send to an address that wallet has never sent to,
builds it, and reads back what the preview says: does the transaction do what it claims,
and is this the first time. Then it prepares the same send again after "landing" is not
possible here — so the second half of the story (the line going away once a send lands)
is the worker's own test, worker/test/firsts.test.ts.

Routes used: wallet/challenge, wallet/verify, memory/consent, send/prepare, send/build.
No /chat, no /pay, no /send/confirm: nothing is signed and nothing is sent.
"""
import json
import os
import secrets
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from eval import Worker, b58, load_state, proxy_url, public_key, signed_in  # noqa: E402


def main():
    state = load_state()
    worker = Worker(proxy_url(), state["device"])
    signed_in(worker, state)
    print(f"signed in as the eval's wallet {b58(public_key(bytes.fromhex(state['seed'])))[:4]}…")

    status, text, _ = worker.post("memory/consent", {"on": True})
    print("memory on:", status)

    # An address this wallet has certainly never sent to: made up here, now.
    fresh = b58(secrets.token_bytes(32))
    said = f"send 0.01 SOL to {fresh}"
    status, text, _ = worker.post("send/prepare", {"to": fresh, "amount": "0.01", "token": "SOL", "said": said})
    if status != 200:
        print("send/prepare:", status, text[:300])
        return 1
    prepared = json.loads(text)
    print("prepared:", prepared.get("amount"), prepared.get("token"), "to", fresh[:4] + "…" + fresh[-4:])

    status, text, _ = worker.post("send/build", {"id": prepared["id"]})
    if status != 200:
        print("send/build:", status, text[:300])
        return 1
    built = json.loads(text)
    preview = built["preview"]
    print()
    print("  does:            ", preview.get("does"))
    print("  grants_power:    ", preview.get("grants_power"))
    print("  first_destination:", preview.get("first_destination"))
    print("  simulation:      ", built["simulation"])
    print("  transaction sent to the phone:", "transaction" in built)
    print()

    # The same question with memory off: the record is not consulted and nothing is claimed.
    worker.post("memory/consent", {"on": False})
    status, text, _ = worker.post("send/prepare", {"to": fresh, "amount": "0.01", "token": "SOL", "said": said})
    second = json.loads(text)
    status, text, _ = worker.post("send/build", {"id": second["id"]})
    print("with memory off, first_destination:", json.loads(text)["preview"].get("first_destination"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
