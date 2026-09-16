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
}

const LIMIT_REACHED =
  'Not run: the lookup limit for this question was reached. Answer with what you already have.'

export async function answerWithTools(options: {
  callModel: (payload: unknown) => Promise<Response>
  base: ModelPayload
  context: Omit<ToolContext, 'signal'>
  now: () => number
}): Promise<LoopResult> {
  const { callModel, base, now } = options
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
  let toolMs = 0
  const outOfTime = () => now() - started >= toolLimits.ms || controller.signal.aborted

  try {
    for (;;) {
      const finalRound = toolCalls.length >= toolLimits.calls || outOfTime()
      if (finalRound && outOfTime()) timedOut = true
      const res = await callModel({
        ...base,
        messages,
        tools: TOOL_DEFINITIONS,
        ...(finalRound ? { tool_choice: { type: 'none' } } : {}),
      })
      rounds++
      const text = await res.text()
      const reply = parse(text)
      input += Number(reply?.usage?.input_tokens ?? 0)
      output += Number(reply?.usage?.output_tokens ?? 0)

      const uses = Array.isArray(reply?.content) ? reply.content.filter((block: any) => block?.type === 'tool_use') : []
      if (!res.ok || !reply || finalRound || reply.stop_reason !== 'tool_use' || uses.length === 0) {
        const usage = { input_tokens: input, output_tokens: output, tool_ms: toolMs, tools: timings.join(',') }
        const body = reply ? JSON.stringify({ ...reply, usage }) : text
        return { status: res.status, body, input, output, rounds, toolCalls, timedOut, toolMs, timings }
      }

      // Every tool_use needs a tool_result, run or not. The allowance is handed
      // out in order; the allowed ones run together.
      const allowance = Math.max(0, toolLimits.calls - toolCalls.length)
      const results = await Promise.all(
        uses.map(async (use: any, index: number) => {
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
export const PROPOSE_SEND = {
  name: 'propose_send',
  description: 'Write down the send the user asked for, exactly as they said it. Heylana checks it and the user confirms and signs it.',
  input_schema: {
    type: 'object',
    properties: {
      to: { type: 'string', description: 'The recipient exactly as the user wrote or said it: an address, or a .skr or .sol name' },
      amount: { type: ['number', 'null'], description: 'The amount; null only if the user said everything or all' },
      token: { type: 'string', enum: ['SOL', 'USDC', 'SKR'] },
    },
    required: ['to', 'amount', 'token'],
  },
}

export interface SendProposal {
  status: number
  body: string
  input: number
  output: number
  action: { type: 'send'; to: string; amount: unknown; token: unknown } | null
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
  if (!res.ok || !reply) return { status: res.status, body: text, input, output, action: null }

  const use = (Array.isArray(reply.content) ? reply.content : []).find(
    (block: any) => block?.type === 'tool_use' && block?.name === PROPOSE_SEND.name,
  )
  const action = use && typeof use.input?.to === 'string' && use.input.to.trim()
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
  return { status: res.status, body, input, output, action }
}
