#!/usr/bin/env python3
"""Cuts scripts/kb/data/*.jsonl into scripts/kb/data/corpus.jsonl: chunks of about 400 tokens.

    python3 scripts/kb/chunk.py

Each chunk: {id, source, url, title, licence, text}. A chunk is whole paragraphs where it
can be (a long paragraph is cut at sentences), about CHUNK_CHARS characters (≈400 tokens at
four characters a token), and repeats the last ~OVERLAP_CHARS of the one before, so an answer
that straddles the cut is still found whole in one of them.

Before cutting: legal pages (terms, agreements, privacy policies) are left out — they answer
no developer's question; code blocks over CODE_LINES lines keep their first lines and say so;
anything under MIN_CHARS (an empty release note) is dropped; Anchor's docs copy of its
changelog is dropped, since the changelog itself is in.
"""
import hashlib, json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
INPUTS = ["stackexchange", "docs", "changelogs", "x_threads"]

CHUNK_CHARS = 1600
OVERLAP_CHARS = 240
CODE_LINES = 30
MIN_CHARS = 150
MIN_CHUNK_CHARS = 80

SKIP_URL = re.compile(r"/(agreement|tou|privacy-policy|terms[^/]*)$|anchor-lang\.com/docs/updates/changelog$")


def shorten_code(text):
    def cut(m):
        lines = m.group(0).split("\n")
        if len(lines) <= CODE_LINES + 2:
            return m.group(0)
        return "\n".join(lines[:CODE_LINES + 1] + ["// … (code shortened)", "```"])
    return re.sub(r"```.*?```", cut, text, flags=re.S)


def pieces(text):
    """Paragraphs, with any longer than a chunk cut at sentences, then at spaces."""
    out = []
    for para in re.split(r"\n\s*\n", text):
        para = para.strip()
        if not para:
            continue
        while len(para) > CHUNK_CHARS:
            cut = max(para.rfind(". ", 0, CHUNK_CHARS), para.rfind("\n", 0, CHUNK_CHARS))
            if cut < CHUNK_CHARS // 2:
                cut = para.rfind(" ", 0, CHUNK_CHARS)
            if cut <= 0:
                cut = CHUNK_CHARS
            out.append(para[:cut + 1].strip())
            para = para[cut + 1:].strip()
        if para:
            out.append(para)
    return out


def chunks(text):
    out, current = [], ""
    for piece in pieces(text):
        if current and len(current) + len(piece) + 2 > CHUNK_CHARS:
            out.append(current)
            # The tail of this chunk opens the next, from a word boundary.
            tail = current[-OVERLAP_CHARS:]
            space = tail.find(" ")
            current = (tail[space + 1:] if space >= 0 else tail) + "\n\n" + piece
        else:
            current = f"{current}\n\n{piece}" if current else piece
    if current:
        out.append(current)
    return out


def main():
    counts, corpus = {}, []
    for name in INPUTS:
        path = os.path.join(DATA, name + ".jsonl")
        if not os.path.exists(path):
            continue
        with open(path) as f:
            for line in f:
                doc = json.loads(line)
                if SKIP_URL.search(doc["url"]):
                    continue
                text = shorten_code(doc["text"]).strip()
                if len(text) < MIN_CHARS:
                    continue
                for i, text_chunk in enumerate(c for c in chunks(text) if len(c) >= MIN_CHUNK_CHARS):
                    key = hashlib.sha1(f"{doc['url']}#{i}".encode()).hexdigest()[:20]
                    corpus.append({"id": key, "source": doc["source"], "url": doc["url"], "title": doc["title"],
                                   "licence": doc["licence"], "text": text_chunk})
                    counts[doc["source"]] = counts.get(doc["source"], 0) + 1
    with open(os.path.join(DATA, "corpus.jsonl"), "w") as f:
        for c in corpus:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    tokens = sum(len(c["text"]) for c in corpus) // 4
    for source, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"  {source}: {n} chunks")
    print(f"corpus: {len(corpus)} chunks, about {tokens} tokens -> {os.path.relpath(os.path.join(DATA, 'corpus.jsonl'))}")


if __name__ == "__main__":
    main()
