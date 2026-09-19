# Smoke test — polish-2-knowledge

For Minos, on the Seeker, with the worker deployed (it is: the knowledge base is bound and
filled, 3,300 chunks).

**Before you start:** Heylana's Anthropic account has hit its spending limit ("You have
reached your specified API usage limits… 2026-10-01"). Steps 1, 4 and 5 need the model:
raise the limit in the Anthropic console first. Steps 2 and 3 work without it — the error
table answers on the phone.

**Live calls this test spends: about 4 chat (1 of them on your own key), about 5 tts.**
Never tap Confirm or Pay.

## 0. Set up

Install this build, run `./scripts/a11y.sh`, open **Heylana**. Watch along with
`adb logcat -s HeylanaState` if you like.

## 1. Who made you (1 chat)

On Home, type **who made you** and tap the arrow.

Expected: "Minos, an independent developer in Lagos…" — Minos only, no surname. Menu →
**Settings**, scroll to **About**: "Made by Minos, an independent developer in Lagos…".

## 2. An error on Solana Stack Exchange (no chat)

1. Menu → hold **Start buddy** until its ring fills.
2. In Chrome, open **solana.stackexchange.com**.
3. Tap the disc, type **what causes AccountDidNotDeserialize**, tap **ask**.

Expected, at once and with no "Thinking…": "AccountDidNotDeserialize (3003): The account's
data doesn't fit the struct the program reads it as. Usual fix: … More:
https://www.anchor-lang.com/docs/features/errors". The log says `error: table hit
name=AccountDidNotDeserialize where=said model=not_asked`.

## 3. Paste an Anchor error (no chat)

Back in Heylana, paste (or type) **Error Code: ConstraintSeeds. Error Number: 2006** into the
message bar and tap the arrow.

Expected: "ConstraintSeeds (2006): The PDA passed doesn't match the seeds and bump… Usual
fix: Derive it on the client with the same seeds… More:
https://www.anchor-lang.com/docs/references/account-constraints".

Also try **custom program error: 0xbbb** — the same as 3003, AccountDidNotDeserialize.

## 4. An answer that cites a source (1–2 chat)

On Home, type **how do priority fees work** and tap the arrow.

Expected: a short answer that names where it came from in a few words, for example "the
Solana docs on fees…" or "the Cookbook's 'How to Add Priority Fees to a Transaction'…". In
the worker's log (`cd worker && npx wrangler tail --format pretty`) the chat line shows
`"kb_hits": 3` (or 1–3).

## 5. Your own key (1 chat, on your key)

1. Menu → **Advanced**. The note says the key is "Kept encrypted in the phone's keystore. It
   goes to Heylana's server with each question, is used for that question only, and is never
   stored or logged there."
2. Enter your Anthropic key and save.
3. In another terminal: `cd worker && npx wrangler tail --format pretty`.
4. Start the buddy, open the Wallet, tap the disc, ask **what's my SOL balance**.

Expected: an answer with your balance (the lookup tool still ran). In the tail, the chat line
says `"key": "user"` and `"tool_calls": ["get_balances"]`, and your key appears nowhere —
search the tail for `sk-ant`: nothing. If your key is on the same Anthropic account as
Heylana's, it will be refused while that account is over its limit: Heylana then says
"Anthropic refused your own key…" or shows the limit message.

5. Menu → **Advanced** → switch your own key off again if you don't want to keep using it.

## Put it back

Menu → hold **Start buddy** to stop it if you want it off.

## The knowledge base, for later

`./scripts/kb/build.sh` rebuilds it (fetch, chunk, embed, store) and prints the counts; it
needs `scripts/kb/.admin_secret` (on this Mac) or `KB_ADMIN_SECRET`. To add X threads, put
them in `scripts/kb/x_threads/` as its README says, then run `./scripts/kb/build.sh`.
