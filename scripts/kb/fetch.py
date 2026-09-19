#!/usr/bin/env python3
"""Fetches the sources of Heylana's Solana knowledge base into scripts/kb/data/*.jsonl.

    python3 scripts/kb/fetch.py all          # or: stackexchange | docs | changelogs | x

One document per line: {source, url, title, licence, text}. Nothing about any user goes in:
only public documentation, public answers and public release notes, each with its source
and licence so an answer can say where it came from.

- stackexchange  Solana Stack Exchange (site=solana): the 800 highest-scoring questions, each
                 with its accepted answer, else its top-voted one. Question title plus answer
                 body as plain text, licence as the site states it (CC BY-SA), author named.
- docs           solana.com/docs (English: core, programs, tokens, rpc) and the Solana Cookbook,
                 from solana-foundation/solana-com; Anchor's docs from otter-sec/anchor; the
                 Solana Mobile docs (Seed Vault, Mobile Wallet Adapter, dApp Store, Android)
                 from solana-mobile/solana-mobile-doc-site. Read from the repos, not the pages.
- changelogs     Agave's GitHub releases from the last 12 months and the headlines of its
                 CHANGELOG.md; Anchor's CHANGELOG.md.
- x              the hand-picked X threads in scripts/kb/x_threads/ (see its README).

Standard library only. GitHub allows 60 unauthenticated API calls an hour; this uses about
ten, and fetches files from raw.githubusercontent.com. Set GITHUB_TOKEN to lift the limit.
"""
import datetime, gzip, html, json, os, re, sys, time, urllib.error, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
UA = "heylana-kb/1 (+https://github.com/solana-foundation)"

# ------------------------------------------------------------------ http


def get(url, accept="application/json"):
    headers = {"User-Agent": UA, "Accept": accept, "Accept-Encoding": "gzip"}
    if "api.github.com" in url and os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as res:
                body = res.read()
                if res.headers.get("Content-Encoding") == "gzip" or body[:2] == b"\x1f\x8b":
                    body = gzip.decompress(body)
                return body.decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            if e.code in (429, 502, 503) and attempt < 3:
                time.sleep(5 * (attempt + 1))
                continue
            raise
    raise RuntimeError("unreachable")


def get_json(url):
    return json.loads(get(url))


def write(name, docs):
    os.makedirs(DATA, exist_ok=True)
    path = os.path.join(DATA, name + ".jsonl")
    with open(path, "w") as f:
        for d in docs:
            if d["text"].strip():
                f.write(json.dumps(d, ensure_ascii=False) + "\n")
    print(f"{name}: {sum(1 for d in docs if d['text'].strip())} documents -> {os.path.relpath(path)}")

# ------------------------------------------------------------------ text


def html_to_text(body):
    """Stack Exchange answer HTML as plain text: code kept, links as their words, tags gone."""
    body = re.sub(r"<pre[^>]*><code[^>]*>(.*?)</code></pre>", lambda m: "\n```\n" + m.group(1) + "\n```\n", body, flags=re.S)
    body = re.sub(r"<br\s*/?>", "\n", body)
    body = re.sub(r"</(p|li|h\d|blockquote|div)>", "\n", body)
    body = re.sub(r"<li[^>]*>", "- ", body)
    body = re.sub(r"<[^>]+>", "", body)
    return re.sub(r"\n{3,}", "\n\n", html.unescape(body)).strip()


def mdx_to_text(source):
    """(title, text) of a Markdown or MDX page: front matter read, imports and JSX tags dropped, links as words."""
    title = ""
    m = re.match(r"^---\n(.*?)\n---\n", source, re.S)
    if m:
        t = re.search(r"^title:\s*[\"']?(.*?)[\"']?\s*$", m.group(1), re.M)
        title = t.group(1).strip() if t else ""
        source = source[m.end():]
    lines, in_code = [], False
    for line in source.splitlines():
        if line.strip().startswith("```"):
            in_code = not in_code
            lines.append(line)
            continue
        if not in_code:
            if re.match(r"^\s*(import|export)\s", line):
                continue
            line = re.sub(r"</?[A-Z][A-Za-z0-9.]*[^>]*>", "", line)         # <Callout>, <Tabs>, </Step>
            line = re.sub(r"<(img|br|hr)[^>]*/?>", "", line)
            line = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", line)                   # images
            line = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", line)               # links as their words
            line = re.sub(r"\{/\*.*?\*/\}", "", line)                           # MDX comments
        lines.append(line)
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()
    if not title:
        h = re.search(r"^#\s+(.+)$", text, re.M)
        title = h.group(1).strip() if h else ""
    return title, text

# ------------------------------------------------------------------ Stack Exchange

SE = "https://api.stackexchange.com/2.3"


def stackexchange(count=800):
    questions = []
    page = 1
    while len(questions) < count:
        data = get_json(f"{SE}/questions?order=desc&sort=votes&site=solana&pagesize=100&page={page}")
        questions += data.get("items", [])
        if not data.get("has_more"):
            break
        page += 1
        time.sleep(data.get("backoff", 0) or 0.2)
    questions = questions[:count]
    best = {}
    ids = [q["question_id"] for q in questions if q.get("answer_count", 0) > 0]
    for i in range(0, len(ids), 100):
        batch = ";".join(str(x) for x in ids[i:i + 100])
        page = 1
        while True:
            data = get_json(f"{SE}/questions/{batch}/answers?order=desc&sort=votes&site=solana&pagesize=100&page={page}&filter=withbody")
            for a in data.get("items", []):
                qid = a["question_id"]
                keep = best.get(qid)
                # The accepted answer, else the highest-voted one.
                if keep is None or (a.get("is_accepted") and not keep.get("is_accepted")) or \
                        (not keep.get("is_accepted") and not a.get("is_accepted") and a.get("score", 0) > keep.get("score", 0)):
                    best[qid] = a
            if not data.get("has_more"):
                break
            page += 1
            time.sleep(data.get("backoff", 0) or 0.2)
        print(f"  answers: {len(best)} of {len(ids)} questions so far, quota left {data.get('quota_remaining')}")
    docs = []
    for q in questions:
        a = best.get(q["question_id"])
        if not a:
            continue
        title = html.unescape(q["title"])
        author = html.unescape((a.get("owner") or {}).get("display_name", "a Stack Exchange user"))
        licence = a.get("content_license") or "CC BY-SA 4.0"
        docs.append({
            "source": "Solana Stack Exchange",
            "url": f"https://solana.stackexchange.com/a/{a['answer_id']}",
            "title": title,
            "licence": f"{licence}, answer by {author}",
            "text": f"Question: {title}\n\nAnswer ({'accepted' if a.get('is_accepted') else 'top voted'}):\n{html_to_text(a['body'])}",
        })
    write("stackexchange", docs)

# ------------------------------------------------------------------ docs repos

REPOS = [
    # (source, repo, branch, licence, path filter, url for a path)
    ("solana.com docs", "solana-foundation/solana-com", "main", "GPL-3.0 (solana-foundation/solana-com)",
     re.compile(r"^apps/docs/content/docs/en/(core|programs|tokens|rpc)/.+\.mdx?$"),
     lambda p: "https://solana.com/docs/" + re.sub(r"(/index)?\.mdx?$", "", p.split("/docs/en/", 1)[1])),
    ("Solana Cookbook", "solana-foundation/solana-com", "main", "GPL-3.0 (solana-foundation/solana-com)",
     re.compile(r"^apps/docs/content/cookbook/.+\.mdx?$"),
     lambda p: "https://solana.com/developers/cookbook/" + re.sub(r"(/?index)?\.mdx?$", "", p.split("/cookbook/", 1)[1])),
    ("Anchor docs", "otter-sec/anchor", "master", "Apache-2.0 (Anchor)",
     re.compile(r"^docs/content/docs/.+\.mdx?$"),
     lambda p: "https://www.anchor-lang.com/docs/" + re.sub(r"(/?index)?\.mdx?$", "", p.split("docs/content/docs/", 1)[1])),
    ("Solana Mobile docs", "solana-mobile/solana-mobile-doc-site", "main", "no licence stated (Solana Mobile docs), quoted with a link",
     re.compile(r"^docs/(?!reference/typescript/.*legacy).+\.mdx?$"),
     lambda p: "https://docs.solanamobile.com/" + re.sub(r"(/?index)?\.mdx?$", "", p[len("docs/"):])),
]


def docs():
    out = []
    for source, repo, branch, licence, wanted, url_of in REPOS:
        tree = get_json(f"https://api.github.com/repos/{repo}/git/trees/{branch}?recursive=1")["tree"]
        paths = sorted(x["path"] for x in tree if x["type"] == "blob" and wanted.match(x["path"]))
        print(f"  {source}: {len(paths)} files")
        for path in paths:
            if "/_snippets/" in path or os.path.basename(path).startswith("_"):
                continue
            raw = get(f"https://raw.githubusercontent.com/{repo}/{branch}/{urllib.parse.quote(path)}", accept="text/plain")
            title, text = mdx_to_text(raw)
            out.append({"source": source, "url": url_of(path), "title": title or os.path.basename(path), "licence": licence, "text": text})
    write("docs", out)

# ------------------------------------------------------------------ changelogs


def changelogs():
    out = []
    year_ago = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=365)
    page = 1
    while True:
        releases = get_json(f"https://api.github.com/repos/anza-xyz/agave/releases?per_page=100&page={page}")
        if not releases:
            break
        for r in releases:
            published = datetime.datetime.fromisoformat((r.get("published_at") or "1970-01-01T00:00:00Z").replace("Z", "+00:00"))
            if published < year_ago or not (r.get("body") or "").strip():
                continue
            _, text = mdx_to_text(r["body"])
            out.append({"source": "Agave release notes", "url": r["html_url"], "title": f"Agave {r['tag_name']} ({published.date()})",
                        "licence": "Apache-2.0 (anza-xyz/agave)", "text": text})
        if len(releases) < 100 or page >= 3:
            break
        page += 1
    for source, repo, branch, licence in [
        ("Agave changelog", "anza-xyz/agave", "master", "Apache-2.0 (anza-xyz/agave)"),
        ("Anchor changelog", "otter-sec/anchor", "master", "Apache-2.0 (Anchor)"),
    ]:
        raw = get(f"https://raw.githubusercontent.com/{repo}/{branch}/CHANGELOG.md", accept="text/plain")
        # One document per release heading: "## [1.18.0] - 2024-..." with its lines under it.
        for m in re.finditer(r"^##\s+(.+?)\n(.*?)(?=^##\s|\Z)", raw, re.M | re.S):
            heading, body = m.group(1).strip(), m.group(2).strip()
            date = re.search(r"(\d{4}-\d{2}-\d{2})", heading)
            if source == "Agave changelog" and date and datetime.date.fromisoformat(date.group(1)) < year_ago.date():
                continue
            if not body:
                continue
            _, text = mdx_to_text(body)
            anchor = re.sub(r"[^a-z0-9]+", "", heading.lower())
            out.append({"source": source, "url": f"https://github.com/{repo}/blob/{branch}/CHANGELOG.md#{anchor}",
                        "title": f"{source.split()[0]} changelog {heading}", "licence": licence, "text": text})
    write("changelogs", out)

# ------------------------------------------------------------------ X threads


def x_threads():
    folder = os.path.join(HERE, "x_threads")
    out = []
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".txt"):
            continue
        with open(os.path.join(folder, name)) as f:
            raw = f.read()
        head, _, body = raw.partition("\n\n")
        fields = dict(line.split(":", 1) for line in head.splitlines() if ":" in line)
        url, author = fields.get("url", "").strip(), fields.get("author", "").strip()
        if not url or not body.strip():
            print(f"  skipped {name}: needs url: and author: lines, a blank line, then the text")
            continue
        out.append({"source": "X", "url": url, "title": fields.get("title", f"Thread by {author}").strip(),
                    "licence": f"quoted from X, by {author}", "text": body.strip()})
    write("x_threads", out)


def main():
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    steps = {"stackexchange": stackexchange, "docs": docs, "changelogs": changelogs, "x": x_threads}
    for name, step in steps.items():
        if what in ("all", name):
            print(f"fetching {name}…")
            step()


if __name__ == "__main__":
    main()
