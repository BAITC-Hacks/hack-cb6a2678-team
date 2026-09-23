import type { ForecastAlert } from '../types'

interface Props {
  alerts: ForecastAlert[]
}

const TYPE_LABEL: Record<ForecastAlert['type'], string> = {
  icing: 'Обледенение',
  storm_cutout: 'Штормовое отключение',
  ramp_down: 'Резкий спад',
  ramp_up: 'Резкий рост',
  low_confidence: 'Низкая уверенность',
  model_physics_gap: 'Расхождение с физикой',
}

const SEVERITY_LABEL: Record<ForecastAlert['severity'], string> = {
  info: 'Инфо',
  warning: 'Внимание',
  critical: 'Критично',
}

function formatRange(from: string, to: string): string {
  const fmt = (ts: string) =>
    new Date(ts).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
  return `${fmt(from)} – ${fmt(to)}`
}

export function AlertsList({ alerts }: Props) {
  if (alerts.length === 0) {
    return <div className="card muted">Предупреждений нет</div>
  }

  return (
    <ul className="alerts-list card">
      {alerts.map((a, i) => (
        <li key={i} className={`alert-row alert-row-${a.severity}`}>
          <span className="alert-indicator" />
          <span className="alert-badge">{SEVERITY_LABEL[a.severity]}</span>
          <span className="alert-type">{TYPE_LABEL[a.type]}</span>
          <span className="alert-time">{formatRange(a.from, a.to)}</span>
          <div className="alert-message">{a.message}</div>
        </li>
      ))}
    </ul>
  )
}
