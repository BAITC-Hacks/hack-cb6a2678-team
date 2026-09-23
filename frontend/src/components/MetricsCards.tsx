import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis } from 'recharts'
import type { TooltipContentProps } from 'recharts'
import type { MetricsResponse } from '../types'

const COLOR_MAE = '#1c69d4'
const AXIS = '#cccccc'
const MUTED = '#6b6b6b'

interface Props {
  metrics: MetricsResponse | null
}

function formatDay(d: string): string {
  return new Date(d).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })
}

function BarTooltip({ active, payload, label }: TooltipContentProps) {
  if (!active || !payload || payload.length === 0) return null
  const entry = payload[0]
  return (
    <div className="chart-tooltip">
      <div className="chart-tooltip-label">{formatDay(String(label))}</div>
      <div className="chart-tooltip-row">
        <span className="chart-tooltip-key" style={{ background: entry.color }} />
        <span className="chart-tooltip-value">
          {typeof entry.value === 'number' ? entry.value.toFixed(3) : '—'}
        </span>
        <span className="chart-tooltip-name">MAE</span>
      </div>
    </div>
  )
}

export function MetricsCards({ metrics }: Props) {
  if (!metrics) return <div className="card muted">Загрузка метрик...</div>

  const improvementPct =
    metrics.baselineMae > 0 ? ((metrics.baselineMae - metrics.mae) / metrics.baselineMae) * 100 : 0
  const better = improvementPct >= 0

  return (
    <div className="metrics-cards">
      <div className="spec-row card">
        <div className="spec-cell">
          <div className="stat-value">{metrics.mae.toFixed(3)}</div>
          <div className="stat-label">MAE</div>
          <div className={`stat-delta ${better ? 'stat-delta-good' : 'stat-delta-bad'}`}>
            {better ? '▼' : '▲'} {Math.abs(improvementPct).toFixed(0)}% vs бейзлайн ({metrics.baselineMae.toFixed(3)})
          </div>
        </div>

        <div className="spec-cell">
          <div className="stat-value">{metrics.rmse.toFixed(3)}</div>
          <div className="stat-label">RMSE</div>
        </div>

        <div className="spec-cell">
          <div className="stat-value">{metrics.mape.toFixed(1)}%</div>
          <div className="stat-label">MAPE</div>
        </div>
      </div>

      {metrics.byDay.length > 0 && (
        <div className="chart-card card byday-panel">
          <div className="stat-label">MAE по дням</div>
          <ResponsiveContainer width="100%" height={140}>
            <BarChart data={metrics.byDay} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
              <XAxis
                dataKey="date"
                tickFormatter={formatDay}
                stroke={AXIS}
                tick={{ fill: MUTED, fontSize: 10 }}
                interval={3}
              />
              <Tooltip
                content={(props) => <BarTooltip {...props} />}
                cursor={{ fill: 'rgba(28,105,212,0.08)' }}
              />
              <Bar dataKey="mae" fill={COLOR_MAE} radius={0} maxBarSize={18} isAnimationActive={false} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  )
}
