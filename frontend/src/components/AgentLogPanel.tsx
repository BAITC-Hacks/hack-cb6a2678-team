import { useEffect, useState } from 'react'
import { getAgentLog, runForecastCycle } from '../api'
import type { AgentLogResponse, AgentStepStatus, HorizonHours } from '../types'

const POLL_INTERVAL_MS = 2000

// /api/agent-log — единственный эндпоинт, где ревизии всё ещё старые (мок,
// не тронутый переходом на реальный ML): 4 плановых цикла в сутки, 00/06/12/18 UTC.
// Не связано с /api/forecast/revisions (там теперь версии по суткам, см. ForecastVersions).
const AGENT_REVISIONS = [1, 2, 3, 4]
const AGENT_REVISION_LABEL: Record<number, string> = { 1: '00 UTC', 2: '06 UTC', 3: '12 UTC', 4: '18 UTC' }

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
}

export function AgentLogPanel({ turbineId, date, horizonHours }: Props) {
  const [revision, setRevision] = useState(1)
  const [log, setLog] = useState<AgentLogResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [running, setRunning] = useState(false)
  const [pollKey, setPollKey] = useState(0)

  // Смена даты/турбины — сбрасываем на плановый цикл 00 UTC.
  useEffect(() => {
    setRevision(1)
  }, [turbineId, date])

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
        <div className="agent-revision-row">
          {AGENT_REVISIONS.map((r) => (
            <button
              key={r}
              type="button"
              className={r === revision ? 'agent-revision-btn active' : 'agent-revision-btn'}
              onClick={() => setRevision(r)}
            >
              {AGENT_REVISION_LABEL[r]}
            </button>
          ))}
        </div>
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
