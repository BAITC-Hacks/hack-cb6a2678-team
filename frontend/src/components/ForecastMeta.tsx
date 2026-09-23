import type { ForecastResponse } from '../types'

interface Props {
  forecast: ForecastResponse
  issueTimeLocal: string | null
  localTz: string | null
}

function formatTime(ts: string): string {
  return new Date(ts).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function ForecastMeta({ forecast, issueTimeLocal, localTz }: Props) {
  return (
    <div className="forecast-meta">
      <span className="forecast-meta-item">
        Погода: {forecast.weatherSource} · выпущена {formatTime(forecast.weatherIssuedAt)}
      </span>
      <span className="forecast-meta-item">Модель: {forecast.modelVersion}</span>
      {issueTimeLocal && localTz && (
        <span className="forecast-meta-item">
          Выпуск прогноза: {issueTimeLocal} ({localTz})
        </span>
      )}
    </div>
  )
}
