import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  FREE_TALKS, WELCOME_BONUS, extendPro, grantWelcome, makeJudge, monthKey, newAccount, planOf,
  resetsAt, spendTalk, standing,
} from '../src/plans.ts'
import { decodeBase58, encodeBase58, isAddress } from '../src/base58.ts'
import { challengeMessage, readSession, signSession, verifySignature } from '../src/session.ts'

const now = new Date('2026-09-15T12:00:00Z')

test('free is thirty talks a month', () => {
  assert.equal(FREE_TALKS, 30)
  const s = standing(newAccount(), 0, now)
  assert.equal(s.plan, 'free')
  assert.equal(s.limit, 30)
  assert.equal(s.skills_cap, 3)
})

test('the welcome bonus is twenty talks, granted once', () => {
  const first = grantWelcome(newAccount())
  assert.equal(first.granted, true)
  assert.equal(first.account.bonus_left, WELCOME_BONUS)
  const again = grantWelcome(first.account)
  assert.equal(again.granted, false)
  assert.equal(again.account.bonus_left, 20)
  assert.equal(standing(first.account, 0, now).limit, 50)
})

test('bonus talks are only spent after the month’s thirty', () => {
  let account = grantWelcome(newAccount()).account
  let used = 0
  for (let i = 0; i < 30; i++) ({ account, used } = spendTalk(account, used, now))
  assert.equal(account.bonus_left, 20)
  ;({ account, used } = spendTalk(account, used, now))
  assert.equal(used, 31)
  assert.equal(account.bonus_left, 19)
  assert.equal(standing(account, used, now).limit, 50, 'the limit shown stays 50 all month')
})

test('with the month and the bonus spent, the next talk is refused', () => {
  let account = grantWelcome(newAccount()).account
  let used = 0
  for (let i = 0; i < 50; i++) ({ account, used } = spendTalk(account, used, now))
  const refused = spendTalk(account, used, now)
  assert.equal(refused.allowed, false)
  assert.equal(refused.used, 50)
})

test('talks roll over at the month boundary, and leftover bonus carries', () => {
  assert.equal(monthKey(new Date('2026-09-30T23:59:59Z')), '2026-09')
  assert.equal(monthKey(new Date('2026-10-01T00:00:00Z')), '2026-10')
  assert.equal(resetsAt(new Date('2026-09-30T23:59:59Z')), '2026-10-01T00:00:00.000Z')
  assert.equal(resetsAt(new Date('2026-12-31T10:00:00Z')), '2027-01-01T00:00:00.000Z')
  const october = standing({ bonus_left: 15, bonus_granted: true }, 0, new Date('2026-10-01T00:00:00Z'))
  assert.equal(october.used, 0)
  assert.equal(october.limit, 45)
})

test('pro and judge are unlimited with ten skills', () => {
  const pro = extendPro(newAccount(), now, 30)
  assert.equal(planOf(pro, now), 'pro')
  assert.equal(standing(pro, 999, now).limit, null)
  assert.equal(standing(pro, 999, now).skills_cap, 10)
  assert.equal(spendTalk(pro, 999, now).allowed, true)
  const judge = makeJudge(newAccount(), '2026-11-09')
  assert.equal(planOf(judge, now), 'judge')
  assert.equal(standing(judge, 0, now).skills_cap, 10)
})

test('paying again extends pro from its end, not from today', () => {
  const once = extendPro(newAccount(), now, 30)
  const twice = extendPro(once, now, 30)
  assert.equal(Date.parse(twice.pro_until!) - Date.parse(once.pro_until!), 30 * 86_400_000)
})

test('lapsed pro is free again', () => {
  const lapsed = { ...newAccount(), pro_until: '2026-09-01T00:00:00.000Z' }
  assert.equal(planOf(lapsed, now), 'free')
})

test('judge lasts to the end of its day and then ends', () => {
  const judge = makeJudge(newAccount(), '2026-11-09')
  assert.equal(planOf(judge, new Date('2026-11-09T23:00:00Z')), 'judge')
  assert.equal(planOf(judge, new Date('2026-11-10T00:00:01Z')), 'free')
})

test('base58 round-trips, leading zeros included', () => {
  const bytes = new Uint8Array([0, 0, 1, 2, 255, 128, 7])
  assert.deepEqual(decodeBase58(encodeBase58(bytes)), bytes)
  assert.equal(encodeBase58(new Uint8Array(32)), '11111111111111111111111111111111')
  assert.equal(isAddress('EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v'), true)
  assert.equal(isAddress('not-an-address'), false)
  assert.equal(decodeBase58('0OIl'), null)
})

test('a real ed25519 signature over the challenge verifies, a wrong one does not', async () => {
  const pair = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']) as CryptoKeyPair
  const pubkey = encodeBase58(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)))
  const message = challengeMessage(pubkey, 'abc123', '2026-09-15T12:00:00.000Z')
  const sig = new Uint8Array(await crypto.subtle.sign({ name: 'Ed25519' }, pair.privateKey, new TextEncoder().encode(message)))
  assert.equal(await verifySignature(pubkey, message, encodeBase58(sig)), true)
  assert.equal(await verifySignature(pubkey, message + ' ', encodeBase58(sig)), false)
  sig[0] ^= 1
  assert.equal(await verifySignature(pubkey, message, encodeBase58(sig)), false)
  assert.equal(await verifySignature(pubkey, message, 'short'), false)
})

test('a session reads back its wallet, and refuses edits and expiry', async () => {
  const t = Date.parse('2026-09-15T12:00:00Z')
  const token = await signSession('Wallet111', 'secret-one', t)
  assert.equal(await readSession(token, 'secret-one', t + 1000), 'Wallet111')
  assert.equal(await readSession(token, 'secret-two', t + 1000), null, 'another secret')
  assert.equal(await readSession(token, 'secret-one', t + 31 * 86_400_000), null, 'expired after 30 days')
  const [payload, mac] = token.split('.')
  const forged = payload.slice(0, -2) + (payload.endsWith('A') ? 'B' : 'A') + '.' + mac
  assert.equal(await readSession(forged, 'secret-one', t), null, 'edited')
})
