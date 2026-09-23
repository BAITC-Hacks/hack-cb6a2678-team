import type { ForecastResponse } from '../types'

interface Props {
  forecast: ForecastResponse
}

function formatTime(ts: string): string {
  return new Date(ts).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function ForecastMeta({ forecast }: Props) {
  return (
    <div className="forecast-meta">
      <span className="forecast-meta-item">
        Погода: {forecast.weatherSource} · выпущена {formatTime(forecast.weatherIssuedAt)}
      </span>
      <span className="forecast-meta-item">Модель: {forecast.modelVersion}</span>
    </div>
  )
}
