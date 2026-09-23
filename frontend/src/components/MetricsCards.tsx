import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { TooltipContentProps } from 'recharts'
import type { MetricsResponse } from '../types'

const COLOR_MAE = '#1c69d4'
const AXIS = '#cccccc'
const MUTED = '#6b6b6b'
const GRID = '#e6e6e6'

interface Props {
  metrics: MetricsResponse | null
}

function LeadTimeTooltip({ active, payload, label }: TooltipContentProps) {
  if (!active || !payload || payload.length === 0) return null
  const entry = payload[0]
  return (
    <div className="chart-tooltip">
      <div className="chart-tooltip-label">+{label}ч</div>
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

// Светло-голубой (низкая ошибка) -> синий бренда (высокая) — один тон, по величине.
function heatColor(value: number, min: number, max: number): string {
  const t = max > min ? (value - min) / (max - min) : 0
  const light = [205, 226, 251] // #cde2fb
  const dark = [28, 105, 212] // #1c69d4
  const mix = light.map((c, i) => Math.round(c + (dark[i] - c) * t))
  return `rgb(${mix.join(',')})`
}

const WEEKDAY_LABELS = ['ПН', 'ВТ', 'СР', 'ЧТ', 'ПТ', 'СБ', 'ВС']

// v1.2: не кликабельно — byDay теперь за январь (отложенный тест), а /api/forecast
// принимает только 31.01–26.02. Клик сюда увёл бы на дату, для которой прогноза нет.
// Точное значение даёт наведение (title).
function CalendarHeatmap({ byDay }: { byDay: MetricsResponse['byDay'] }) {
  if (byDay.length === 0) return null

  const values = byDay.map((d) => d.mae)
  const min = Math.min(...values)
  const max = Math.max(...values)

  const firstWeekday = (new Date(`${byDay[0].date}T00:00:00Z`).getUTCDay() + 6) % 7 // 0=Пн
  const cells: Array<{ date: string; mae: number } | null> = [
    ...Array<null>(firstWeekday).fill(null),
    ...byDay.map((d) => ({ date: d.date, mae: d.mae })),
  ]

  return (
    <div>
      <div className="calendar-weekdays">
        {WEEKDAY_LABELS.map((w) => (
          <span key={w}>{w}</span>
        ))}
      </div>
      <div className="calendar-grid">
        {cells.map((cell, i) =>
          cell ? (
            <span
              key={cell.date}
              className="calendar-cell"
              style={{ background: heatColor(cell.mae, min, max) }}
              title={`${cell.date} · MAE ${cell.mae.toFixed(3)}`}
            >
              {new Date(`${cell.date}T00:00:00Z`).getUTCDate()}
            </span>
          ) : (
            <span key={`pad-${i}`} className="calendar-cell calendar-cell-empty" />
          ),
        )}
      </div>
    </div>
  )
}

export function MetricsCards({ metrics }: Props) {
  if (!metrics) return <div className="muted">Загрузка метрик...</div>

  const better =
    metrics.baselineMae !== null && metrics.baselineMae > 0 ? metrics.mae <= metrics.baselineMae : null
  const improvementPct =
    better !== null ? Math.abs(((metrics.baselineMae! - metrics.mae) / metrics.baselineMae!) * 100) : null

  const powerCurveImprovementPct =
    metrics.powerCurveBaselineMae && metrics.powerCurveBaselineMae > 0
      ? ((metrics.powerCurveBaselineMae - metrics.mae) / metrics.powerCurveBaselineMae) * 100
      : null
  const betterThanPowerCurve = (powerCurveImprovementPct ?? 0) >= 0

  return (
    <div className="metrics-cards">
      <div className="spec-row card">
        <div className="spec-cell">
          <div className="stat-value">{metrics.mae.toFixed(3)}</div>
          <div className="stat-label">MAE</div>
          {better !== null && improvementPct !== null && (
            <div className={`stat-delta ${better ? 'stat-delta-good' : 'stat-delta-bad'}`}>
              {better ? '▼' : '▲'} {improvementPct.toFixed(0)}% vs persistence ({metrics.baselineMae!.toFixed(3)})
            </div>
          )}
          {powerCurveImprovementPct !== null && (
            <div className={`stat-delta ${betterThanPowerCurve ? 'stat-delta-good' : 'stat-delta-bad'}`}>
              {betterThanPowerCurve ? '▼' : '▲'} {Math.abs(powerCurveImprovementPct).toFixed(0)}% vs power curve (
              {metrics.powerCurveBaselineMae?.toFixed(3)})
            </div>
          )}
        </div>

        <div className="spec-cell">
          <div className="stat-value">{metrics.rmse.toFixed(3)}</div>
          <div className="stat-label">RMSE</div>
        </div>

        <div className="spec-cell">
          <div className="stat-value">{metrics.mape.toFixed(1)}%</div>
          <div className="stat-label">MAPE</div>
        </div>

        {metrics.bias !== null && (
          <div className="spec-cell">
            <div className="stat-value">
              {metrics.bias >= 0 ? '+' : ''}
              {metrics.bias.toFixed(3)}
            </div>
            <div className="stat-label">Смещение{metrics.bias >= 0 ? ' (завышает)' : ' (занижает)'}</div>
          </div>
        )}

        {metrics.intervalCoverage !== null && (
          <div className="spec-cell">
            <div className="stat-value">{(metrics.intervalCoverage * 100).toFixed(0)}%</div>
            <div className="stat-label">Покрытие P10–P90</div>
          </div>
        )}
      </div>

      {metrics.byDay.length > 0 && (
        <div className="chart-card card byday-panel">
          <div className="stat-label">MAE по дням отложенного теста</div>
          <CalendarHeatmap byDay={metrics.byDay} />
        </div>
      )}

      {metrics.byLeadTime.length > 0 && (
        <div className="chart-card card byday-panel">
          <div className="stat-label">MAE по заблаговременности</div>
          <ResponsiveContainer width="100%" height={140}>
            <LineChart data={metrics.byLeadTime} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid stroke={GRID} vertical={false} />
              <XAxis
                dataKey="leadHour"
                stroke={AXIS}
                tick={{ fill: MUTED, fontSize: 10 }}
                tickFormatter={(h: number) => `${h}ч`}
                interval={5}
              />
              <YAxis hide domain={[0, 'dataMax']} />
              <Tooltip content={(props) => <LeadTimeTooltip {...props} />} />
              <Line
                type="monotone"
                dataKey="mae"
                stroke={COLOR_MAE}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  )
}
