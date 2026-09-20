/**
 * Anthropic tool use, run inside the worker.
 *
 * The model may ask for lookups; the worker runs them and hands the results
 * back, round after round, until the model answers. The app never sees a tool:
 * it gets the final answer in the same shape as a question without tools.
 *
 * A question gets at most [toolLimits.calls] lookups and [toolLimits.ms] of
 * looking. After that the model is asked once more with tools switched off, and
 * answers with what it has.
 */
import { TOOL_DEFINITIONS, runTool, type ToolContext } from './tools.ts'
import { authorize, definition, type Decision } from './registry.ts'
import { inUserWords } from './policy.ts'

/**
 * The tools a request offers: all of them, or only the ones named. A sign
 * explanation needs explain_address alone, and every definition left out is input
 * the model is not paid to read, twice.
 */
export function toolsNamed(names: unknown): typeof TOOL_DEFINITIONS {
  if (!Array.isArray(names)) return TOOL_DEFINITIONS
  return TOOL_DEFINITIONS.filter((tool) => names.includes(tool.name))
}

/** Mutable so tests can shorten them; nothing else changes them. */
export const toolLimits = { calls: 4, ms: 12_000 }

export interface ModelPayload {
  model: string
  max_tokens: number
  system?: string
  messages: unknown[]
}

export interface LoopResult {
  status: number
  /** The final Anthropic reply, with usage summed across every round. */
  body: string
  input: number
  output: number
  rounds: number
  toolCalls: string[]
  timedOut: boolean
  /** Time spent in lookups, added up, and each one as "name:123ms". */
  toolMs: number
  timings: string[]
  /** What the registry decided for every tool call the model made. */
  decisions: Decision[]
}

const LIMIT_REACHED =
  'Not run: the lookup limit for this question was reached. Answer with what you already have.'

/**
 * The answer's own shape, as a tool.
 *
 * Thirteen of sixty developer questions in the eval came back as markdown prose with
 * `stop_reason: end_turn` — the model had made a lookup, read the results and then wrote
 * an essay instead of the contract. Asking again costs a second question and usually gets
 * the same thing. So the final call after a lookup forces this tool, exactly as a send
 * forces `propose_send`: there is no prose to write, only fields to fill in.
 */
export const ANSWER_TOOL = {
  name: 'answer',
  description:
    'Give your final answer to the user. Always answer with this tool: never write the answer as prose.',
  input_schema: {
    type: 'object',
    properties: {
      say: {
        type: 'string',
        description:
          '1 to 3 short sentences, written to be read aloud. No markdown, no headings, no bullet points, no urls, no code.',
      },
      steps: {
        type: 'array',
        description:
          'Instead of say, and only when the answer walks the user around the screen: up to four sentences, each with the element it is about.',
        items: {
          type: 'object',
          properties: {
            text: { type: 'string' },
            point_at: { type: ['integer', 'null'] },
          },
          required: ['text'],
        },
      },
      point_at: {
        type: ['integer', 'null'],
        description: 'The id of the one element on screen the answer is about, or null.',
      },
      task: {
        type: ['object', 'null'],
        description: 'Only when this is something to do step by step: {goal, done}.',
        properties: { goal: { type: 'string' }, done: { type: 'boolean' } },
      },
      code: {
        type: 'string',
        description:
          'For a developer: the smallest snippet that works, under 12 lines, with a comment naming the library and version. It is shown, never read aloud.',
      },
      cite: {
        type: 'array',
        items: { type: 'string' },
        description: 'The url of each knowledge-base page you used.',
      },
    },
    required: [],
  },
} as const

/**
 * The room an answer written as a tool needs.
 *
 * The phone asks for 300 tokens, which is plenty for two spoken sentences; the same two
 * sentences inside a tool call are JSON with field names, escaping and possibly a snippet
 * in `code`, and the first live run came back cut off mid-word. The extra is a ceiling, not
 * a target: the prompt still asks for 1 to 3 short sentences and the app still caps them.
 */
export const ANSWER_MAX_TOKENS = 700

/** A filled-in [ANSWER_TOOL] as the reply body the rest of the worker already understands. */
export function answerFromTool(input: any, usage: Record<string, unknown>): string {
  const reply: Record<string, unknown> = { say: '', point_at: null, task: null }
  const steps = Array.isArray(input?.steps) ? input.steps : null
  if (steps && steps.length > 0) reply.say = steps
  else if (typeof input?.say === 'string') reply.say = input.say
  if (typeof input?.point_at === 'number') reply.point_at = input.point_at
  if (input?.task && typeof input.task === 'object') reply.task = input.task
  if (typeof input?.code === 'string' && input.code.trim()) reply.code = input.code
  if (Array.isArray(input?.cite)) reply.cite = input.cite
  return JSON.stringify({
    type: 'message',
    role: 'assistant',
    stop_reason: 'end_turn',
    content: [{ type: 'text', text: JSON.stringify(reply) }],
    usage,
  })
}

export async function answerWithTools(options: {
  callModel: (payload: unknown) => Promise<Response>
  base: ModelPayload
  context: Omit<ToolContext, 'signal'>
  now: () => number
  /** Which tools to offer; all of them unless the app asked for fewer. */
  tools?: typeof TOOL_DEFINITIONS
  /** The user's own words: a send's recipient must be in them, never only on the screen. */
  said?: string | null
  /** Whether the answer is forced into [ANSWER_TOOL] rather than left to prose. */
  forceShape?: boolean
}): Promise<LoopResult> {
  const { callModel, base, now } = options
  const offered = options.tools ?? TOOL_DEFINITIONS
  // Offered every round, so the tool list — and with it the cached prefix — does not change
  // between them; forced only on the last one.
  const forceShape = options.forceShape === true
  const onTable = forceShape ? [...offered, ANSWER_TOOL] : offered
  const started = now()
  const controller = new AbortController()
  const deadline = setTimeout(() => controller.abort(), toolLimits.ms)
  const context: ToolContext = { ...options.context, signal: controller.signal }

  let messages = [...base.messages]
  let input = 0
  let output = 0
  let rounds = 0
  let timedOut = false
  const toolCalls: string[] = []
  const timings: string[] = []
  const decisions: Decision[] = []
  const offeredNames = offered.map((tool) => tool.name)
  let toolMs = 0
  const outOfTime = () => now() - started >= toolLimits.ms || controller.signal.aborted

  try {
    for (;;) {
      const finalRound = toolCalls.length >= toolLimits.calls || outOfTime()
      if (finalRound && outOfTime()) timedOut = true
      const res = await callModel({
        ...base,
        messages,
        ...(forceShape ? { max_tokens: Math.max(base.max_tokens, ANSWER_MAX_TOKENS) } : {}),
        tools: onTable,
        // With the answer itself on the table, every round is a tool call: a lookup while
        // there are lookups left, the answer on the last one. Prose has nowhere to come out.
        ...(finalRound
          ? forceShape
            ? { tool_choice: { type: 'tool', name: ANSWER_TOOL.name } }
            : { tool_choice: { type: 'none' } }
          : forceShape
            ? { tool_choice: { type: 'any' } }
            : {}),
      })
      rounds++
      const text = await res.text()
      const reply = parse(text)
      input += Number(reply?.usage?.input_tokens ?? 0)
      output += Number(reply?.usage?.output_tokens ?? 0)

      const uses = Array.isArray(reply?.content) ? reply.content.filter((block: any) => block?.type === 'tool_use') : []
      // The answer tool is the answer: whichever round it comes in, that is the end of it.
      const answered = uses.find((use: any) => use.name === ANSWER_TOOL.name)
      if (res.ok && answered) {
        const usage = { input_tokens: input, output_tokens: output, tool_ms: toolMs, tools: timings.join(',') }
        const body = answerFromTool(answered.input, usage)
        return { status: res.status, body, input, output, rounds, toolCalls, timedOut, toolMs, timings, decisions }
      }
      if (!res.ok || !reply || finalRound || reply.stop_reason !== 'tool_use' || uses.length === 0) {
        const usage = { input_tokens: input, output_tokens: output, tool_ms: toolMs, tools: timings.join(',') }
        const body = reply ? JSON.stringify({ ...reply, usage }) : text
        return { status: res.status, body, input, output, rounds, toolCalls, timedOut, toolMs, timings, decisions }
      }

      // Every tool_use needs a tool_result, run or not. The allowance is handed
      // out in order; the allowed ones run together.
      const allowance = Math.max(0, toolLimits.calls - toolCalls.length)
      const results = await Promise.all(
        uses.map(async (use: any, index: number) => {
          // The registry first: unknown, not offered, R3/R4, or arguments off the schema never run.
          let decision = authorize(use.name, use.input, offeredNames)
          if (decision.decision === 'allowed' && use.name === 'prepare_send' && !inUserWords(use.input?.to, options.said)) {
            decision = { decision: 'rejected', tool: decision.tool, class: decision.class, reason: 'recipient_not_in_user_words' }
          }
          decisions.push(decision)
          if (decision.decision === 'rejected') {
            return {
              type: 'tool_result', tool_use_id: use.id, is_error: true,
              content: JSON.stringify({ error: 'rejected', reason: decision.reason }),
            }
          }
          if (index >= allowance || outOfTime()) {
            return { type: 'tool_result', tool_use_id: use.id, content: LIMIT_REACHED, is_error: true }
          }
          toolCalls.push(String(use.name))
          const remaining = toolLimits.ms - (now() - started)
          const began = now()
          const result = await withDeadline(runTool(String(use.name), use.input, context), remaining, {
            error: 'timed_out',
            detail: 'The lookup took too long.',
          })
          const ms = now() - began
          toolMs += ms
          timings.push(`${use.name}:${ms}ms`)
          if ((result as any)?.error === 'timed_out') timedOut = true
          return { type: 'tool_result', tool_use_id: use.id, content: JSON.stringify(result) }
        }),
      )
      messages = [...messages, { role: 'assistant', content: reply.content }, { role: 'user', content: results }]
    }
  } finally {
    clearTimeout(deadline)
  }
}

function parse(text: string): any {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function withDeadline<T>(work: Promise<T>, ms: number, late: T): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const tooLate = new Promise<T>((resolve) => {
    timer = setTimeout(() => resolve(late), Math.max(0, ms))
  })
  return Promise.race([work, tooLate]).finally(() => clearTimeout(timer))
}

// ---------------------------------------------------------------------- send

/**
 * The only tool offered when the question is a send, and the model has to use
 * it: it writes down what the user asked to send and nothing else. No prose comes
 * back, so nothing the model says about a send ever reaches the user — the app
 * writes the confirmation, and /send/prepare does the checking.
 */
export const PROPOSE_SEND = definition('propose_send')

export interface SendProposal {
  status: number
  body: string
  input: number
  output: number
  action: { type: 'send'; to: string; amount: unknown; token: unknown } | null
  decision: Decision | null
}

export async function proposeSend(options: {
  callModel: (payload: unknown) => Promise<Response>
  base: ModelPayload
}): Promise<SendProposal> {
  const res = await options.callModel({
    ...options.base,
    tools: [PROPOSE_SEND],
    tool_choice: { type: 'tool', name: PROPOSE_SEND.name },
  })
  const text = await res.text()
  const reply = parse(text)
  const input = Number(reply?.usage?.input_tokens ?? 0)
  const output = Number(reply?.usage?.output_tokens ?? 0)
  if (!res.ok || !reply) return { status: res.status, body: text, input, output, action: null, decision: null }

  const use = (Array.isArray(reply.content) ? reply.content : []).find(
    (block: any) => block?.type === 'tool_use' && block?.name === PROPOSE_SEND.name,
  )
  // Written down exactly as the schema says, or not at all.
  const decision = use ? authorize(use.name, use.input, [PROPOSE_SEND.name]) : null
  const action = use && decision?.decision === 'allowed' && typeof use.input?.to === 'string' && use.input.to.trim()
    ? { type: 'send' as const, to: use.input.to, amount: use.input.amount ?? null, token: use.input.token }
    : null
  // The shape every other answer has, with no words in it: the app writes them.
  const answer = { say: '', point_at: null, task: null, action }
  const body = JSON.stringify({
    type: 'message',
    role: 'assistant',
    stop_reason: 'end_turn',
    content: [{ type: 'text', text: JSON.stringify(answer) }],
    usage: { input_tokens: input, output_tokens: output, tool_ms: 0, tools: '' },
  })
  return { status: res.status, body, input, output, action, decision }
}

// ------------------------------------------------------------- quick actions

/**
 * The only tool offered when the app says the question is a quick action, and the
 * model has to use it. Like a send: the model writes down what was asked, no prose
 * comes back, the app checks every part against the user's own words and writes the
 * line Heylana says. Its schema is the registry's.
 */
export const PROPOSE_ACTION = definition('propose_action')

const ACTION_FIELDS = [
  'hour', 'minutes', 'message', 'seconds', 'app', 'url', 'query', 'number', 'name', 'text', 'command', 'state', 'page',
] as const

export interface ActionProposal {
  status: number
  body: string
  input: number
  output: number
  action: Record<string, unknown> | null
  decision: Decision | null
}

export async function proposeAction(options: {
  callModel: (payload: unknown) => Promise<Response>
  base: ModelPayload
}): Promise<ActionProposal> {
  const res = await options.callModel({
    ...options.base,
    tools: [PROPOSE_ACTION],
    tool_choice: { type: 'tool', name: PROPOSE_ACTION.name },
  })
  const text = await res.text()
  const reply = parse(text)
  const input = Number(reply?.usage?.input_tokens ?? 0)
  const output = Number(reply?.usage?.output_tokens ?? 0)
  if (!res.ok || !reply) return { status: res.status, body: text, input, output, action: null, decision: null }

  const use = (Array.isArray(reply.content) ? reply.content : []).find(
    (block: any) => block?.type === 'tool_use' && block?.name === PROPOSE_ACTION.name,
  )
  const decision = use ? authorize(use.name, use.input, [PROPOSE_ACTION.name]) : null
  let action: Record<string, unknown> | null = null
  if (use && decision?.decision === 'allowed' && typeof use.input?.intent === 'string') {
    action = { type: 'intent', intent: use.input.intent }
    for (const field of ACTION_FIELDS) {
      const value = use.input[field]
      if (value !== null && value !== undefined && value !== '') action[field] = value
    }
  }
  return { status: res.status, body: forcedAnswer(action, input, output), input, output, action, decision }
}

/** The shape every other answer has, with no words in it: the app writes them. */
export function forcedAnswer(action: Record<string, unknown> | null, input: number, output: number): string {
  const answer = { say: '', point_at: null, task: null, action }
  return JSON.stringify({
    type: 'message',
    role: 'assistant',
    stop_reason: 'end_turn',
    content: [{ type: 'text', text: JSON.stringify(answer) }],
    usage: { input_tokens: input, output_tokens: output, tool_ms: 0, tools: '' },
  })
}
