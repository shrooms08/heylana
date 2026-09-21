---
id: jupiter
name: Jupiter
package: ag.jup.jupiter.android
version: 3
author: Heylana
summary: Jupiter Mobile - swap, limit and recurring orders, markets and the account.
privacy: Reference text only. It reads nothing and sends nothing.
---
Read off this Seeker's screens, 2026-09-21. It may open behind its own lock ("Authenticate", "Use PIN").

Screens
Bottom bar: Home, Markets, Trade, Account, Spend. Home has Send, Deposit, Scan, Swap, Earn, Perps, Predict, More.
Trade: tabs Swap, Perps, Predictions; under Swap: Market, Limit, Recurring and a clock icon (history). A Sell card with a token chip ("SOL"), the balance and the amount; a Buy card ("USDC"); a two-arrow button flips them; a green "Enter Amount" button; a keypad with MAX, 75%, 50%, CLEAR.
Limit adds "Sell SOL when above" (Price or MCap), "Expires after", "Platform Fee". Recurring adds "Every", "Over" (orders), "Price Range (Optional)".
Account: the balance, Deposit, History; Tokens, DeFi, NFTs.
The avatar (top left) opens Switch, Manage, Notifications, Settings, FAQ, Support. The globe (top right) is the dApp browser.

Swapping
1. Tap Trade, then Swap, then Market.
2. Tap the Sell chip for what to pay with, the Buy chip for what to get (picker unverified).
3. Type the amount, or MAX, 75%, 50%. The button reads "Enter Amount" until there is one, "Insufficient SOL balance" if it is too much.
4. Tap it to review: rate, fee and slippage (unverified: not walked).
Heylana stops at that review screen and tells the user what they are about to sign. It never taps confirm and never approves; the signing glance takes over.

Fees (docs): 0.1% to 0.2% on most pairs; limit and recurring 0.1%. Prices are live estimates.

Warnings
Unknown tokens can drain a wallet: check the name and the verified tick. "No routes found" is thin liquidity, not something to retry.
