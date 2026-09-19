---
id: jupiter
name: Jupiter
package: ag.jup.jupiter.android
version: 2
author: Heylana
summary: Jupiter Mobile - swaps, limit and recurring orders, and Jupiter Lend.
privacy: Reference text only. It reads nothing and sends nothing.
---
From Jupiter's public docs. On this Seeker (2026-09-19) the app opens behind its own lock, a screen titled "Authenticate" asking to touch the fingerprint sensor or "Use PIN"; the screens behind it were not walked (unverified).
If the user asks about that screen: it is Jupiter's own app lock, unlocked with their fingerprint or PIN.

Screens
Home tab: total balance and 1-day change, Deposit, Trade, Earn and More.
Portfolio tab: holdings, History (All, Swaps, Transfers, Limit, Recurring), Manage Tokens and Clean Account.
Trade tab: swaps. The globe icon at the top right opens the dApp browser.
Deposit: buy with a card, or receive to the wallet address.
Earn: puts SOL or stablecoins into Jupiter Lend.

Common tasks
Swapping:
1. Open Trade.
2. Pick the token to pay with and the token to get, then the amount.
3. Check the rate and fee, then confirm.
Limit order: on Trade choose Limit, set the price and amount. The minimum is 5 dollars.
Recurring (DCA): on Trade choose Recurring, set the amount and how often.
Recovering rent: Portfolio, then Clean Account, closes empty token accounts and returns their SOL.

Fees (from the docs)
Stablecoin to stablecoin: none. SOL or bluechips to stablecoins: 0.1%. Other pairs: 0.2%. Tokens under a day old: 0.5%. Limit and recurring: 0.1%.

Warnings
Swaps and limit orders always pay out to the user's own wallet.
Unknown tokens can be built to drain a wallet. Do not trade tokens you cannot identify.
A Quick Account (social login) is lost if the login is lost.
"No routes found" means too little liquidity, not an error to retry many times.
