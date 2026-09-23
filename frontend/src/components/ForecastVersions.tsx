import type { ForecastRevision } from '../types'

interface Props {
  targetDate: string
  revisions: ForecastRevision[]
}

const REVISION_LABEL: Record<number, string> = {
  1: 'За 2 дня',
  2: 'Накануне',
}

function formatTime(ts: string): string {
  return new Date(ts).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

// v1.2: это не переключатель — /api/forecast всегда отдаёт единственную (последнюю)
// версию прогноза. Здесь просто показываем, как менялась оценка для этих суток между
// выпуском "за 2 дня" и более свежим "накануне" — доказательство повторного расчёта из ТЗ.
export function ForecastVersions({ targetDate, revisions }: Props) {
  if (revisions.length === 0) return null

  return (
    <div className="versions-row">
      <span className="versions-label">Версии на {targetDate}:</span>
      {revisions.map((r) => (
        <span key={r.revision} className="version-chip">
          <span className="version-chip-name">{REVISION_LABEL[r.revision] ?? `Версия ${r.revision}`}</span>
          <span className="version-chip-value">{(r.meanPower * 100).toFixed(0)}%</span>
          {r.changeVsPreviousPct !== null && (
            <span className={r.changeVsPreviousPct >= 0 ? 'version-chip-delta up' : 'version-chip-delta down'}>
              {r.changeVsPreviousPct >= 0 ? '+' : ''}
              {r.changeVsPreviousPct.toFixed(0)}%
            </span>
          )}
          <span className="version-chip-time">{formatTime(r.forecastIssuedAt)}</span>
        </span>
      ))}
    </div>
  )
}
