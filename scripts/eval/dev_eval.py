#!/usr/bin/env python3
"""
The developer eval: sixty questions a Solana developer actually asks, scored.

`scripts/eval.py` checks that Heylana's answers keep their shape — the JSON the app reads,
the word cap, no full addresses, the numbers coming off the screen. This one checks whether
the answers are **right**: that the points a correct answer must contain are in it, that it
cites something when it should, that it does not invent an API that has never existed, and
that it says which version it is talking about when the fact depends on one.

Each question is sent exactly as the phone sends a developer's question — the system prompt
and the user message come from `scripts/eval/dev_request.json`, which the app's own
`DevRequestTest` writes from `HeylanaPrompt`. The questions, the points and the sources are
in `scripts/eval/dev_set.jsonl`, written by hand.

    python3 scripts/eval/dev_eval.py                 # all sixty
    python3 scripts/eval/dev_eval.py --group errors  # one group
    python3 scripts/eval/dev_eval.py --only se-pda-bump err-2006
    python3 scripts/eval/dev_eval.py --failures-of run-1.json   # only what failed last time

It spends one /chat per question and nothing else: no send is prepared, nothing is signed,
and the wallet it signs in as is the eval's own throwaway, never the phone's.
"""
import argparse
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "scripts"))

from eval import Worker, proxy_url, reply_object, say_text, signed_in  # noqa: E402

DEV_SET = os.path.join(HERE, "dev_set.jsonl")
REQUEST = os.path.join(HERE, "dev_request.json")

# ------------------------------------------------------------------ what must never appear

# Names that sound like the API and are not in it. A model reaching for one of these is
# guessing, and a developer who copies it loses an hour. Every one was checked against the
# current libraries on 2026-09-20 and does not exist; the real name is on the right.
#
# They are matched as exact strings on purpose: `requestUnits`, `mintTo` and
# `confirmTransaction` are all real, and only the longer forms below are invented.
INVENTED = {
    "getminimumrentexemption": "getMinimumBalanceForRentExemption",
    "deriveprogramaddress": "PublicKey.findProgramAddressSync",
    "setpriorityfee": "ComputeBudgetProgram.setComputeUnitPrice",
    "requestcomputeunits": "ComputeBudgetProgram.setComputeUnitLimit",
    "createassociatedaccount": "createAssociatedTokenAccount",
    "spl-token mint-to": "spl-token mint",
    "solana confirm-transaction": "solana confirm",
    "connection.getpriorityfee": "connection.getRecentPrioritizationFees",
    "publickey.findpda": "PublicKey.findProgramAddressSync",
}

# What counts as saying which version you mean.
VERSION_MARKS = ("as of", "as at", "september 2026", "2026")


def load_state_at(name):
    """
    A throwaway wallet of the eval's own, one per name.

    Free gives a wallet twenty welcome talks and thirty a month, and the set is sixty
    questions, so a full run is split across two of these rather than borrowing the
    phone's account — whose talks, memory and history are the user's, not the eval's.
    """
    path = os.path.join(HERE, f".state_{name}.json")
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    import secrets
    import uuid
    state = {"device": str(uuid.uuid4()), "seed": secrets.token_hex(32)}
    with open(path, "w") as f:
        json.dump(state, f)
    os.chmod(path, 0o600)
    return state


def load_set():
    with open(DEV_SET) as f:
        return [json.loads(line) for line in f if line.strip()]


def load_request():
    if not os.path.exists(REQUEST):
        sys.exit("No dev_request.json: run the app's unit tests to write it (DevRequestTest).")
    with open(REQUEST) as f:
        return json.load(f)


VERDICTS = os.path.join(HERE, "dev_verdicts.json")


def load_verdicts():
    """
    Which questions the phone treats as how-Solana-works, which of those ask for code, and
    which it would date.

    `ProxyClient.ask` adds those lines itself, per question, so they are not in the fixed
    prefix. `DevRequestTest` runs the phone's own classifier over the set and writes the
    verdicts here, so the eval asks exactly what Heylana asks and no more.
    """
    if not os.path.exists(VERDICTS):
        sys.exit("No dev_verdicts.json: run the app's unit tests to write it (DevRequestTest).")
    with open(VERDICTS) as f:
        return json.load(f)


def body_for(request, case, verdicts):
    question = case["q"]
    verdict = verdicts.get(case["id"], {})
    asked = request["user_prefix"] + question + request["user_suffix"]
    # What ProxyClient.ask adds: the code-first line when code was asked for, else the
    # words line for a question about how Solana works; the date when the fact moves.
    if verdict.get("mechanics"):
        asked += "\n\n" + (request["dev_line"] if verdict.get("code") else request["mechanics_line"])
        if verdict.get("dated"):
            asked += request["dev_version_line"]
    return {
        "mode": request["mode"],
        "max_tokens": request["max_tokens"],
        "system": request["system"],
        "messages": [{"role": "user", "content": asked}],
        "said": question,
        "tools": request.get("tools", True),
    }


# ------------------------------------------------------------------ scoring

def answer_text(obj):
    """Everything the developer would read: the words, and the code if the reply carries any."""
    parts = [say_text(obj)]
    code = obj.get("code")
    if isinstance(code, str):
        parts.append(code)
    return "\n".join(p for p in parts if p)


def missing_points(text, points):
    """The point groups this answer does not make. A group passes on any of its wordings."""
    low = text.lower()
    return [group for group in points if not any(w.lower() in low for w in group)]


def invented_names(text):
    low = text.lower()
    return sorted({name for name in INVENTED if name in low})


def has_chip(obj):
    sources = obj.get("sources")
    return isinstance(sources, list) and len(sources) > 0


def version_pinned(text):
    low = text.lower()
    return any(mark in low for mark in VERSION_MARKS)


def score(case, obj, text):
    """Every check this case asks for, as (name, passed, note)."""
    checks = []
    missing = missing_points(text, case["points"])
    checks.append(("points", not missing, ", ".join("/".join(g) for g in missing)))
    if case.get("chip"):
        checks.append(("chip", has_chip(obj), "" if has_chip(obj) else "no source"))
    made_up = invented_names(text)
    checks.append(("real-api", not made_up, ", ".join(f"{n} (real: {INVENTED[n]})" for n in made_up)))
    if case.get("version"):
        pinned = version_pinned(text)
        checks.append(("version", pinned, "" if pinned else "not dated"))
    return checks


# ------------------------------------------------------------------ the run

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", nargs="*", default=[])
    parser.add_argument("--skip", nargs="*", default=[])
    parser.add_argument("--group", nargs="*", default=[])
    parser.add_argument("--failures-of", default=None, help="a saved run: ask only what failed in it")
    parser.add_argument("--save", default=None, help="write the run to this file")
    parser.add_argument("--worst", type=int, default=10)
    parser.add_argument("--state", default="dev", help="which throwaway wallet to ask as")
    parser.add_argument("--rescore", nargs="*", default=[],
                        help="score saved runs again against the current set, asking nothing")
    args = parser.parse_args()

    cases = load_set()
    if args.rescore:
        return rescore(args.rescore, {c["id"]: c for c in cases}, args.worst)
    if args.group:
        cases = [c for c in cases if c["group"] in args.group]
    if args.only:
        cases = [c for c in cases if c["id"] in args.only]
    if args.skip:
        cases = [c for c in cases if c["id"] not in args.skip]
    if args.failures_of:
        with open(args.failures_of) as f:
            before = json.load(f)
        failed = {r["id"] for r in before["rows"] if not r["passed"]}
        cases = [c for c in cases if c["id"] in failed]
    if not cases:
        sys.exit("No cases selected.")

    request = load_request()
    verdicts = load_verdicts()
    state = load_state_at(args.state)
    worker = Worker(proxy_url(), state["device"])
    signed_in(worker, state)
    if os.environ.get("HEYLANA_JUDGE_CODE"):
        worker.post("judge", {"code": os.environ["HEYLANA_JUDGE_CODE"]})

    rows, calls = [], 0
    for case in cases:
        status, raw, ms = worker.post("chat", body_for(request, case, verdicts))
        calls += 1
        obj = reply_object(raw) if status == 200 else None
        if obj is None:
            # Kept so an unreadable reply can be read afterwards without asking again.
            rows.append({
                "id": case["id"], "group": case["group"], "passed": False, "words": 0,
                "checks": [("reply", False, f"status {status}")], "say": "",
                "raw": raw[-1200:],
            })
            continue
        text = answer_text(obj)
        checks = score(case, obj, text)
        rows.append({
            "id": case["id"], "group": case["group"], "passed": all(p for _, p, _ in checks),
            "words": len(say_text(obj).split()), "ms": ms, "checks": checks,
            "say": say_text(obj), "sources": obj.get("sources") or [],
            # Kept so a re-score reads what the live score read: the code counts too.
            "code": obj.get("code") if isinstance(obj.get("code"), str) else None,
        })
        print(".", end="", flush=True)
    print()

    table(rows)
    passed = sum(1 for r in rows if r["passed"])
    print(f"\n{passed} of {len(rows)} passed, {calls} /chat calls")
    by_group = {}
    for r in rows:
        got, total = by_group.get(r["group"], (0, 0))
        by_group[r["group"]] = (got + (1 if r["passed"] else 0), total + 1)
    print("  " + "   ".join(f"{g}: {got} of {total}" for g, (got, total) in sorted(by_group.items())))

    worst = [r for r in rows if not r["passed"]][: args.worst]
    if worst:
        print(f"\nThe {len(worst)} worst:")
        for r in worst:
            why = "; ".join(f"{name}: {note}" for name, ok, note in r["checks"] if not ok)
            print(f"  {r['id']:<26} {why}")
            print(f"      said: {r['say'][:150]}")

    if args.save:
        with open(args.save, "w") as f:
            json.dump({"at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "rows": rows}, f, indent=1)
        print(f"\nrun saved to {args.save}")
    return 0 if passed == len(rows) else 1


def rescore(paths, by_id, worst):
    """
    The same scoring, run again over runs already paid for.

    An answer is what it is; only the expectations move. Re-scoring keeps a change to the
    set honest — every question is judged by one rule — without buying the answers twice.
    """
    rows = []
    for path in paths:
        with open(path) as f:
            for row in json.load(f)["rows"]:
                case = by_id.get(row["id"])
                if case is None:
                    continue
                if not row.get("say"):
                    rows.append({**row, "checks": [("reply", False, "unreadable")], "passed": False})
                    continue
                obj = {"say": row["say"], "sources": row.get("sources") or [], "code": row.get("code")}
                checks = score(case, obj, answer_text(obj))
                rows.append({**row, "checks": checks, "passed": all(p for _, p, _ in checks)})
    table(rows)
    passed = sum(1 for r in rows if r["passed"])
    print(f"\n{passed} of {len(rows)} passed, 0 /chat calls (scored again from saved runs)")
    by_group = {}
    for r in rows:
        got, total = by_group.get(r["group"], (0, 0))
        by_group[r["group"]] = (got + (1 if r["passed"] else 0), total + 1)
    print("  " + "   ".join(f"{g}: {got} of {total}" for g, (got, total) in sorted(by_group.items())))
    return 0


def table(rows):
    header = ("case", "group", "result", "words", "chip", "failed checks")
    lines = []
    for r in rows:
        failed = ",".join(name for name, ok, _ in r["checks"] if not ok)
        chip = "yes" if any(name == "chip" and ok for name, ok, _ in r["checks"]) else (
            "-" if not any(name == "chip" for name, _, _ in r["checks"]) else "no")
        lines.append((r["id"], r["group"][:6], "pass" if r["passed"] else "FAIL", str(r["words"]), chip, failed))
    widths = [max(len(h), *(len(line[i]) for line in lines)) for i, h in enumerate(header)]
    print("  ".join(h.ljust(w) for h, w in zip(header, widths)))
    print("  ".join("-" * w for w in widths))
    for line in lines:
        print("  ".join(c.ljust(w) for c, w in zip(line, widths)))


if __name__ == "__main__":
    raise SystemExit(main())
