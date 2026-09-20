---
title: Why do validators need SOL just to keep running? (as of 2026-09)
url: https://docs.anza.xyz/operations/validator-initialize
source: Anza docs
licence: Apache-2.0 (anza-xyz/agave), summarised by Heylana
---
As of September 2026. A validator votes on every slot it agrees with, and a vote is an ordinary transaction submitted from its identity account, with an ordinary transaction fee. Voting is therefore a running cost in SOL — on the order of 1 SOL a day at current slot times — whether or not the validator earns anything that epoch. Stake rewards, paid into delegators' stake accounts at the end of each epoch from the validator's vote credits less its commission, are what is meant to cover it; a validator with too little stake pays more in votes than it earns. That cost is also why a validator keeps a funded identity account separate from its vote account and its withdraw authority.
