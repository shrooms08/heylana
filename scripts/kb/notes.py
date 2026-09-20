#!/usr/bin/env python3
"""
Heylana's own notes, into the knowledge base.

`fetch.py` brings in other people's pages. This brings in the ones written here, in
`scripts/kb/notes/`, because the eval found holes nothing on the web filled in the shape
Heylana needs: the Anchor constraint and error lists, the SPL and Token-2022 instruction
sets, the three command lines, what a transaction may weigh this month, and how the
Solana Mobile pieces fit together.

Every note carries the same front matter a fetched page would — a title, the url of the
primary source it was checked against, that source's name and its licence — and every
chunk is dated, because half of what is in them is true only for a particular month.

    python3 scripts/kb/notes.py            # write scripts/kb/data/notes.jsonl
    python3 scripts/kb/notes.py --upload   # and send it to /kb/ingest

The chunking is chunk.py's: whole paragraphs, about 1,600 characters, 240 of overlap.
"""
import argparse
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
NOTES = os.path.join(HERE, "notes")
OUT = os.path.join(HERE, "data", "notes.jsonl")

CHUNK_CHARS = 1600
OVERLAP_CHARS = 240
DATED = "as of 2026-09"


def front_matter(text):
    """The `---` block at the top, and the body after it."""
    if not text.startswith("---"):
        raise ValueError("a note needs front matter")
    _, block, body = text.split("---", 2)
    meta = {}
    for line in block.strip().splitlines():
        key, _, value = line.partition(":")
        meta[key.strip()] = value.strip()
    for needed in ("title", "url", "source", "licence"):
        if needed not in meta:
            raise ValueError(f"a note needs {needed}")
    if not meta["url"].startswith("https://"):
        raise ValueError(f"{meta['title']}: the url must be the https source it was checked against")
    return meta, body.strip()


def paragraphs(body):
    return [p.strip() for p in body.split("\n\n") if p.strip()]


def chunks_of(body):
    """Whole paragraphs, about CHUNK_CHARS each, with the tail of the last one carried over."""
    out, current = [], ""
    for para in paragraphs(body):
        if current and len(current) + len(para) + 2 > CHUNK_CHARS:
            out.append(current)
            current = (current[-OVERLAP_CHARS:] + "\n\n" + para) if OVERLAP_CHARS else para
        else:
            current = f"{current}\n\n{para}" if current else para
    if current:
        out.append(current)
    return out


def notes():
    if not os.path.isdir(NOTES):
        sys.exit(f"No notes in {NOTES}")
    for name in sorted(os.listdir(NOTES)):
        if not name.endswith(".md"):
            continue
        with open(os.path.join(NOTES, name)) as f:
            meta, body = front_matter(f.read())
        if DATED not in body.lower() and DATED not in meta["title"].lower():
            sys.exit(f"{name}: every note says when it was true — put \"{DATED}\" in it")
        yield name, meta, body


def build():
    rows = []
    for name, meta, body in notes():
        pieces = chunks_of(body)
        for i, text in enumerate(pieces):
            # The title rides on every chunk (the worker embeds title-then-text), so a
            # chunk from the middle of a note still says what it is about and when.
            rows.append({
                "id": hashlib.sha1(f"heylana-note:{name}#{i}".encode()).hexdigest()[:20],
                "source": meta["source"],
                "url": meta["url"],
                "title": meta["title"],
                "licence": meta["licence"],
                "text": text,
            })
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--upload", action="store_true")
    args = parser.parse_args()

    rows = build()
    by_note = {}
    for row in rows:
        by_note[row["title"]] = by_note.get(row["title"], 0) + 1
    for title, count in by_note.items():
        print(f"{count:>3} chunks  {title}")
    print(f"\n{len(rows)} chunks, {sum(len(r['text']) for r in rows) // 1000}k characters -> {OUT}")

    if args.upload:
        sys.path.insert(0, HERE)
        import upload as uploader
        uploader.main_with(OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
