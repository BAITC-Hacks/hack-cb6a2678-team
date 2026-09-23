import type { ForecastSummary } from '../types'

interface Props {
  summary: ForecastSummary
}

function formatTime(ts: string): string {
  return new Date(ts).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function ForecastSummaryTiles({ summary }: Props) {
  return (
    <div className="spec-row card">
      <div className="spec-cell">
        <div className="stat-value">{(summary.meanPower * 100).toFixed(0)}%</div>
        <div className="stat-label">Средняя мощность</div>
      </div>
      <div className="spec-cell">
        <div className="stat-value">{(summary.maxPower * 100).toFixed(0)}%</div>
        <div className="stat-label">Пик · {formatTime(summary.maxPowerAt)}</div>
      </div>
      <div className="spec-cell">
        <div className="stat-value">{summary.fullLoadHours.toFixed(1)}</div>
        <div className="stat-label">Часов на номинале</div>
      </div>
      <div className="spec-cell">
        <div className="stat-value">{summary.lowPowerHours}</div>
        <div className="stat-label">Часов простоя</div>
      </div>
    </div>
  )
}
