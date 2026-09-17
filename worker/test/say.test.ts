import { test } from 'node:test'
import assert from 'node:assert/strict'
import { MAX_SEGMENTS, checkedBody, checkedReply, validSegments } from '../src/say.ts'

const segment = (text: string, point_at: number | null = null) => ({ text, point_at })

test('a string say is left exactly as it is', () => {
  assert.equal(validSegments('Tap Swap at the bottom.'), null)
  const { reply, segments } = checkedReply({ say: 'Tap Swap.', point_at: 3, task: null })
  assert.deepEqual(reply, { say: 'Tap Swap.', point_at: 3, task: null })
  assert.equal(segments, 1)
})

test('segments are kept in order, at most four, each with a text and an id or null', () => {
  const say = [
    segment('Swaps live on the Swap tab.', 2),
    segment('Pick what you pay with here.', 5),
    segment('Then check the rate.', null),
    segment('Confirm when it looks right.', 9),
    segment('One too many.', 1),
  ]
  const checked = checkedReply({ say })
  assert.equal(checked.segments, MAX_SEGMENTS)
  assert.deepEqual(checked.reply.say, say.slice(0, MAX_SEGMENTS))
})

test('malformed pieces are dropped, not passed on', () => {
  const checked = checkedReply({
    say: [
      segment('Good one.', 2),
      { text: '   ', point_at: 1 },
      { text: 'No id field is fine.' },
      { point_at: 4 },
      'a bare string',
      { text: 'Bad id.', point_at: -3 },
      { text: 'Bad id too.', point_at: 1.5 },
    ],
  })
  assert.deepEqual(checked.reply.say, [
    segment('Good one.', 2), segment('No id field is fine.', null), segment('Bad id.', null), segment('Bad id too.', null),
  ])
})

test('one piece that points at nothing is just a string; nothing usable is an empty say', () => {
  assert.equal(checkedReply({ say: [segment('Just this.', null)] }).reply.say, 'Just this.')
  assert.deepEqual(checkedReply({ say: [segment('Here.', 3)] }).reply.say, [segment('Here.', 3)])
  const empty = checkedReply({ say: [{ text: '' }] })
  assert.equal(empty.reply.say, '')
  assert.equal(empty.segments, 0)
})

test('the body is rewritten only where a reply was really checked', () => {
  const bodyOf = (reply: unknown) => JSON.stringify({ content: [{ type: 'text', text: JSON.stringify(reply) }] })
  const tidy = checkedBody(bodyOf({ say: [segment('One.', 1), { text: 'Two.', point_at: 'x' }], point_at: null, task: null }))
  assert.deepEqual(JSON.parse(JSON.parse(tidy.body).content[0].text).say, [segment('One.', 1), segment('Two.', null)])
  assert.equal(tidy.segments, 2)

  const same = bodyOf({ say: 'Tap Swap.', point_at: 2, task: null })
  assert.equal(checkedBody(same).body, same)

  // A tool-use body, prose, or something unparseable is never touched.
  const toolUse = JSON.stringify({ content: [{ type: 'tool_use', name: 'propose_send', input: { to: 'bob.skr' } }] })
  assert.equal(checkedBody(toolUse).body, toolUse)
  assert.equal(checkedBody('not json at all').body, 'not json at all')
  const prose = bodyOf('hello')
  assert.equal(checkedBody(prose).body, prose)
})
