# SMOKE TEST — the ears, again

**Read this first: there is something to fix before the test will pass, and it is
not in the app.**

I measured your deployed worker from here before changing any code:

```
POST /stt-token  ->  502
{"category":"INSUFFICIENT_PERMISSIONS",
 "message":"Your account does not have the required scope to perform that action",
 "details":"Check that your account has the 'keys:write' scope for this project."}
```

**The Deepgram key in your worker is not allowed to make keys**, and making a
short-lived key for the phone is the whole of what `/stt-token` does. It has
never once succeeded. Every `ears=android` line you sent me was this, wearing the
wrong label.

## Fix that first — 0 calls

1. Open the **Deepgram console → API keys**.
2. **Create a new key** with the **Owner** or **Administrator** role. A plain
   usage key will not do: it needs `keys:write`.
3. From `worker/`:

```
npx wrangler secret put DEEPGRAM_API_KEY
npx wrangler deploy
```

4. Check it from the Mac before touching the phone — this costs nothing and mints
   a key that expires in two minutes:

```
curl -s -X POST -H "X-Heylana-Device: 00000000-0000-4000-8000-000000000000" \
  -d '{}' https://heylana-proxy.heylana.workers.dev/stt-token
```

**Expected:** `{"key":"…","expires_in":120}`.
**If it still says** `{"reason":"deepgram_scope", …}`, the new key still lacks
the scope — do not go on to the phone, it cannot work yet.

Then install the app and run `./scripts/a11y.sh` as usual.

---

## Budget for this test: 2 stt, 2 chat, 2 tts

## 1. The Kamino test — 1 stt, 1 chat, 1 tts

Open your **wallet**. **Press and hold the buddy** and say:

**"what does the Kamino earn thing do"**

Hold it a moment before you start speaking, as you naturally would.

**In the log** (`adb logcat -s HeylanaState`):

```
ears=deepgram after=…ms token_ms=… socket_ms=… keyterms=16
```

- `token_ms` should be around **1100** the first time, `socket_ms` around
  **1100**, and `after=` under **2500**.
- If it says `ears=android`, **the reason is now the useful part** — send me the
  whole line. `token_scope_keys_write` means the key is still wrong;
  `token_timeout` or `socket_timeout` means the window is too tight from Lagos
  and I will raise it; `token_unreachable_…` names the network fault.

**On screen:** the words appear as you speak, and the transcript **spells
Kamino**. You may also see `deepgram: flushed …ms recorded while connecting` —
that is the start of your sentence being saved rather than lost while the socket
was still opening.

## 2. Again, within a minute — 1 stt, 1 chat, 1 tts

**Hold the buddy** and say **"where is the seed vault"**.

Expect `ears=deepgram` again with a **much smaller `after=`**, and
`deepgram: token_ms=… cached=true` — the key was already borrowed, so only the
socket had to open. Around a second rather than two.

---

## What the numbers mean, if you want to judge the window yourself

Measured from Lagos, on your worker, today:

| | cold | warm |
|---|---|---|
| borrow a key (`/stt-token` round trip) | 1.52s | 1.03–1.14s |
| open the socket to Deepgram | 1.33s | 1.11s |
| **both, which is what a first hold pays** | **~2.6s** | **~2.2s** |

The window is **2500ms** from the moment you touch the disc. That covers a warm
path comfortably and a cold one by a hair. **If step 1 comes back
`socket_timeout` or `token_timeout` with `after=` near 2500, tell me and I will
raise it to 3000** — it is one constant, in one place, with a test on it.

---

## What to do if something goes wrong

- **`ears=android reason=token_scope_keys_write`** — the Deepgram key still
  cannot mint keys. Back to the top of this file.
- **`ears=android reason=token_timeout`** — quote `token_ms`; the window needs
  raising.
- **`ears=android reason=socket_timeout`** — quote `socket_ms`.
- **`ears=deepgram` but the transcript is empty** — say whether you saw
  `deepgram: flushed …ms` and how long you held before speaking.
- **Kamino still comes back as "come in oh"** — quote the transcript and the
  `keyterms=` count.

---

## Pass criteria

- `/stt-token` answers with a key from the Mac before the phone is touched.
- Both holds say `ears=deepgram`, and the second `after=` is smaller than the
  first.
- The transcript spells Kamino.
- Nothing crashes, and the test costs 2 stt, 2 chat and 2 tts.
