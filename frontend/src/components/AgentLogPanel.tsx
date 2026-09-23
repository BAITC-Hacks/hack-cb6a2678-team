import { useEffect, useState } from 'react'
import { getAgentLog, runForecastCycle } from '../api'
import type { AgentLogResponse, AgentStepStatus, HorizonHours } from '../types'

const POLL_INTERVAL_MS = 2000

const STATUS_LABEL: Record<AgentStepStatus, string> = {
  success: 'Готово',
  failed: 'Ошибка',
  retrying: 'Повтор',
  running: 'Выполняется',
}

// Лог "в процессе", только если последний шаг ещё не завершён — так отличаем
// живой ручной запуск (MockDataService.manualLog) от планового лога
// (MockDataService.scheduledLog), который всегда приходит уже целиком.
function isInProgress(log: AgentLogResponse | null): boolean {
  if (!log || log.steps.length === 0) return false
  const last = log.steps[log.steps.length - 1]
  return last.status === 'running' || last.status === 'retrying'
}

function formatTime(ts: string): string {
  return new Date(ts).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

interface Props {
  turbineId: string
  date: string
  horizonHours: HorizonHours
  revision: number
}

export function AgentLogPanel({ turbineId, date, horizonHours, revision }: Props) {
  const [log, setLog] = useState<AgentLogResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [running, setRunning] = useState(false)
  const [pollKey, setPollKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    let intervalId: number | null = null

    const stop = () => {
      if (intervalId !== null) {
        window.clearInterval(intervalId)
        intervalId = null
      }
    }

    const load = () => {
      getAgentLog(date, turbineId, revision)
        .then((res) => {
          if (cancelled) return
          setLog(res)
          setError(null)
          if (!isInProgress(res)) stop()
        })
        .catch((e: Error) => {
          if (cancelled) return
          setError(e.message)
          stop()
        })
    }

    setLog(null)
    load()
    intervalId = window.setInterval(load, POLL_INTERVAL_MS)

    return () => {
      cancelled = true
      stop()
    }
  }, [turbineId, date, revision, pollKey])

  const handleRun = () => {
    setRunning(true)
    setError(null)
    runForecastCycle({ turbineId, date, horizonHours })
      .then(() => setPollKey((k) => k + 1))
      .catch((e: Error) => setError(e.message))
      .finally(() => setRunning(false))
  }

  const live = isInProgress(log)

  return (
    <div className="agent-log-panel">
      <div className="agent-log-header">
        <button type="button" className="run-btn" onClick={handleRun} disabled={running}>
          {running ? 'Запуск...' : 'Запустить новый цикл'}
        </button>
        {live && <span className="agent-log-live">выполняется</span>}
      </div>

      {error && <pre className="error">Ошибка: {error}</pre>}

      {log ? (
        <ol className="agent-log-steps">
          {log.steps.map((s, i) => (
            <li key={i} className={`agent-step agent-step-${s.status}`}>
              <span className="agent-step-indicator" />
              <span className="agent-step-badge">{STATUS_LABEL[s.status]}</span>
              <span className="agent-step-name">{s.stepName}</span>
              {s.tool && <span className="agent-step-tool">{s.tool}</span>}
              <span className="agent-step-time">{formatTime(s.timestamp)}</span>
              {s.details && <div className="agent-step-details">{s.details}</div>}
            </li>
          ))}
        </ol>
      ) : (
        <p className="muted">Загрузка лога агента...</p>
      )}
    </div>
  )
}
