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
        const body = reply ? JSON.stringify({ ...reply, usage: { input_tokens: input, output_tokens: output } }) : text
        return { status: res.status, body, input, output, rounds, toolCalls, timedOut }
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
          const result = await withDeadline(runTool(String(use.name), use.input, context), remaining, {
            error: 'timed_out',
            detail: 'The lookup took too long.',
          })
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
