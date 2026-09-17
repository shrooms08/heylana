#!/usr/bin/env python3
"""Extracts the golden vectors Heylana's orb port is tested against.

    python3 scripts/orbs_golden.py <path to thinking-orbs>/spec/orbs-golden.json

Writes app/src/test/resources/orbs/golden.txt.gz with only the four states Heylana
draws (working, listening, composing, breathing), as plain text a JVM unit test can
read without a JSON library:

    opts <state> <size> <speed> key=value ...
    case <state> <size> <t> <dotCount>
    <x> <y> <z> <r> <white> <a>        (one line per dot, in draw order)

The vectors are from thinking-orbs by Jakub Antalik, MIT licence.
"""
import gzip, json, os, sys

STATES = ("working", "listening", "composing", "breathing")

golden = json.load(open(sys.argv[1]))
out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "orbs", "golden.txt.gz")
os.makedirs(os.path.dirname(out), exist_ok=True)
lines = [
    f"# thinking-orbs {golden['sourceLibrary']['version']} golden vectors (MIT, Jakub Antalik); tolerance {golden['tolerance']}"
]
for key, resolved in golden["resolved"].items():
    state, size = key.rsplit("-", 1)
    if state not in STATES:
        continue
    opts = " ".join(f"{k}={v!r}" for k, v in resolved["opts"].items())
    lines.append(f"opts {state} {size} {resolved['speed']!r} {opts}")
for case in golden["cases"]:
    if case["state"] not in STATES:
        continue
    assert case["lineCount"] == 0
    lines.append(f"case {case['state']} {case['size']} {case['t']!r} {case['dotCount']}")
    d = case["dots"]
    for i in range(0, len(d), 6):
        lines.append(" ".join(repr(v) for v in d[i:i + 6]))
with gzip.open(out, "wt") as f:
    f.write("\n".join(lines) + "\n")
print(out, len(lines), "lines")
