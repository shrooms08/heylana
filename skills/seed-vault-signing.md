---
id: seed-vault-signing
name: Seed Vault signing
package: com.solanamobile.seedvaultimpl
version: 1
author: Heylana
summary: The Seeker's secure signing screen, where every request is approved or declined.
privacy: Reference text only. It reads nothing and sends nothing.
---
What it is
Seed Vault keeps the keys in the phone's secure hardware. Apps never see them. When an app wants a transaction or a message signed, Seed Vault shows the request and only the user can approve it, with the fingerprint or the Seed Vault password.
It can hold up to 8 seeds, and the user decides which apps may use each one.

The request screen (unverified: exact layout)
The app asking, and what it asks for: a transaction, or a message.
For a transaction: the amounts leaving and arriving, the destination (shortened), and the network fee.
A button to decline or cancel, and one to approve that asks for the fingerprint.

How to read one
A message moves nothing, but it can prove ownership or log in to an app.
A transaction can move funds. Match every amount and the destination with what was expected.
If the screen shows more leaving than expected, or a token not asked about, decline.

Warnings
Drainers: requests that move everything, or grant another address authority over tokens.
Nothing is sent until approved here, and nothing can be undone after.
A shortened address only shows its ends. Check it matches who was meant.
No app ever needs the seed phrase. Seed Vault never asks for it during a request.
