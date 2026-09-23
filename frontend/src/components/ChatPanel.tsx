import { useRef, useState, type FormEvent } from 'react'
import { agentChat } from '../api'
import { renderMarkdown } from '../markdown'
import type { AgentToolCall } from '../types'

interface ChatExchange {
  id: number
  message: string
  answer: string | null
  toolCalls: AgentToolCall[]
  error: string | null
  pending: boolean
  durationMs: number | null
}

function formatArgs(args: Record<string, unknown>): string {
  return Object.entries(args)
    .map(([k, v]) => `${k}=${String(v)}`)
    .join(', ')
}

function isLlmUnavailable(message: string): boolean {
  return /ollama|llm is unavailable/i.test(message)
}

export function ChatPanel() {
  const [input, setInput] = useState('')
  const [exchanges, setExchanges] = useState<ChatExchange[]>([])
  const nextId = useRef(0)

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    const message = input.trim()
    if (!message) return

    const id = nextId.current++
    setExchanges((prev) => [
      ...prev,
      { id, message, answer: null, toolCalls: [], error: null, pending: true, durationMs: null },
    ])
    setInput('')

    agentChat({ message })
      .then((res) => {
        setExchanges((prev) =>
          prev.map((ex) =>
            ex.id === id
              ? { ...ex, answer: res.answer, toolCalls: res.toolCalls, durationMs: res.durationMs, pending: false }
              : ex,
          ),
        )
      })
      .catch((e: Error) => {
        setExchanges((prev) => prev.map((ex) => (ex.id === id ? { ...ex, error: e.message, pending: false } : ex)))
      })
  }

  return (
    <div className="chat-panel">
      <div className="chat-log">
        {exchanges.length === 0 && (
          <p className="muted">Спросите агента, например: «Будет ли простой у t1 завтра?»</p>
        )}
        {exchanges.map((ex) => (
          <div key={ex.id} className="chat-exchange">
            <div className="chat-question">{ex.message}</div>

            {ex.pending && <div className="chat-pending">Агент думает — локальная LLM, может занять до нескольких минут...</div>}

            {ex.error &&
              (isLlmUnavailable(ex.error) ? (
                <div className="error">
                  LLM-агент сейчас недоступен: локальная модель (Ollama) не запущена.
                  <div className="error-detail">{ex.error}</div>
                </div>
              ) : (
                <div className="error">Ошибка: {ex.error}</div>
              ))}

            {ex.answer && (
              <>
                <div className="chat-answer">{renderMarkdown(ex.answer)}</div>
                {ex.toolCalls.length > 0 && (
                  <div className="chat-tool-calls">
                    {ex.toolCalls.map((tc, i) => (
                      <span key={i} className={tc.ok ? 'chat-tool-call' : 'chat-tool-call chat-tool-call-error'}>
                        {tc.tool}({formatArgs(tc.args)})
                      </span>
                    ))}
                  </div>
                )}
                {ex.durationMs !== null && (
                  <div className="chat-duration">{(ex.durationMs / 1000).toFixed(1)} с</div>
                )}
              </>
            )}
          </div>
        ))}
      </div>

      <form className="chat-form" onSubmit={handleSubmit}>
        <input
          className="chat-input"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Спросите агента..."
        />
        <button type="submit" className="run-btn" disabled={!input.trim()}>
          Спросить
        </button>
      </form>
    </div>
  )
}
