# Hand-picked X threads

Minos chooses these: up to 40 threads from Solana core developers that explain something
worth knowing (how a feature works, why a change was made). Heylana never fetches X itself.

One thread per file, named anything ending in `.txt`, for example `toly-local-fee-markets.txt`:

```
url: https://x.com/aeyakovenko/status/1234567890
author: Anatoly Yakovenko
title: Local fee markets, explained

The whole thread as plain text, posts one after another, in order.
Keep the words as written; leave out images, replies from other people and links you do
not need.
```

- The first lines are `key: value` pairs; `url` and `author` are required, `title` is
  optional (it defaults to "Thread by <author>").
- A blank line ends them; everything after it is the thread's text.
- The answer that uses a thread names its title and links to its url, so pick threads
  whose author would be happy to be quoted, and never paste anyone's private messages.

`scripts/kb/build.sh` picks the files up on its next run.
