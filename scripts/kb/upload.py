#!/usr/bin/env python3
"""Sends scripts/kb/data/corpus.jsonl to the worker's /kb/ingest in batches of 50.

    python3 scripts/kb/upload.py [--from N]

The worker embeds each batch with Workers AI and upserts it into Vectorize; ids are stable
(a hash of url and chunk number), so running it again replaces rather than duplicates, and
--from resumes after a failure. Needs KB_ADMIN_SECRET in the environment or in
scripts/kb/.admin_secret (git-ignored), and the proxy address from HEYLANA_PROXY or
local.properties. Then searches three questions, with no model involved, as a check.
"""
import json, os, sys, time, urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
BATCH = 50


def proxy_url():
    if os.environ.get("HEYLANA_PROXY"):
        return os.environ["HEYLANA_PROXY"].rstrip("/")
    with open(os.path.join(ROOT, "local.properties")) as f:
        for line in f:
            if line.startswith("heylana.proxyUrl="):
                return line.split("=", 1)[1].strip().rstrip("/")
    sys.exit("No proxy address: set HEYLANA_PROXY or heylana.proxyUrl in local.properties.")


def secret():
    if os.environ.get("KB_ADMIN_SECRET"):
        return os.environ["KB_ADMIN_SECRET"]
    path = os.path.join(HERE, ".admin_secret")
    if os.path.exists(path):
        return open(path).read().strip()
    sys.exit("No KB_ADMIN_SECRET: set it, or put it in scripts/kb/.admin_secret.")


def post(base, key, route, body):
    req = urllib.request.Request(f"{base}/{route}", data=json.dumps(body).encode(), method="POST", headers={
        "content-type": "application/json", "X-Heylana-KB-Admin": key, "User-Agent": "heylana-kb/1"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=120) as res:
                return json.loads(res.read())
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="ignore")[:200]
            if e.code >= 500 and attempt < 3:
                time.sleep(3 * (attempt + 1))
                continue
            sys.exit(f"{route}: {e.code} {detail}")
        except (urllib.error.URLError, OSError) as e:
            # A dropped connection (a TLS alert, a reset): the batch is safe to send again.
            if attempt < 3:
                time.sleep(3 * (attempt + 1))
                continue
            sys.exit(f"{route}: {e}")


def main():
    start = int(sys.argv[sys.argv.index("--from") + 1]) if "--from" in sys.argv else 0
    main_with(os.path.join(HERE, "data", "corpus.jsonl"), start)


def main_with(path, start=0):
    """Upload any corpus file: the fetched pages, or Heylana's own notes."""
    base, key = proxy_url(), secret()
    with open(path) as f:
        corpus = [json.loads(line) for line in f]
    done = 0
    for i in range(start, len(corpus), BATCH):
        result = post(base, key, "kb/ingest", {"chunks": corpus[i:i + BATCH]})
        done += result.get("upserted", 0)
        if (i // BATCH) % 10 == 0:
            print(f"  uploaded {i + len(corpus[i:i + BATCH])} of {len(corpus)}", flush=True)
    print(f"upload: {done} chunks embedded and stored (from {start})")
    for q in ["how do priority fees work", "AccountDidNotDeserialize", "Mobile Wallet Adapter authorize"]:
        found = post(base, key, "kb/search", {"query": q, "k": 3}).get("results", [])
        print(f"  search \"{q}\": " + "; ".join(f"{r['title'][:50]} ({r['score']})" for r in found))


if __name__ == "__main__":
    main()
