import {
  Area,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { TooltipContentProps } from 'recharts'
import type { ForecastPoint } from '../types'

const COLOR_PREDICTED = '#1c69d4'
const COLOR_ACTUAL = '#e22718'
const GRID = '#e6e6e6'
const AXIS = '#cccccc'
const MUTED = '#6b6b6b'
const SURFACE = '#ffffff'

interface Props {
  points: ForecastPoint[]
  showActual: boolean
}

interface ChartRow extends ForecastPoint {
  bandLow: number | null
  bandRange: number | null
}

function formatTick(ts: string): string {
  return new Date(ts).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function ChartTooltip({ active, payload, label }: TooltipContentProps) {
  if (!active || !payload || payload.length === 0) return null
  const rows = payload.filter((entry) => entry.dataKey !== 'bandLow' && entry.dataKey !== 'bandRange')
  return (
    <div className="chart-tooltip">
      <div className="chart-tooltip-label">{formatTick(String(label))}</div>
      {rows.map((entry) => (
        <div key={String(entry.dataKey)} className="chart-tooltip-row">
          <span className="chart-tooltip-key" style={{ background: entry.color }} />
          <span className="chart-tooltip-value">
            {typeof entry.value === 'number' ? entry.value.toFixed(3) : '—'}
          </span>
          <span className="chart-tooltip-name">{entry.name}</span>
        </div>
      ))}
    </div>
  )
}

export function ForecastChart({ points, showActual }: Props) {
  const hasActual = showActual && points.some((p) => p.actualPower !== null)
  const hasBand = points.some((p) => p.p10 !== null && p.p90 !== null)

  const data: ChartRow[] = points.map((p) => ({
    ...p,
    bandLow: p.p10,
    bandRange: p.p10 !== null && p.p90 !== null ? p.p90 - p.p10 : null,
  }))

  return (
    <div className="chart-card">
      <ResponsiveContainer width="100%" height={360}>
        <ComposedChart data={data} margin={{ top: 8, right: 16, left: 0, bottom: 8 }}>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis
            dataKey="timestamp"
            tickFormatter={formatTick}
            stroke={AXIS}
            tick={{ fill: MUTED, fontSize: 12 }}
            minTickGap={32}
          />
          <YAxis
            domain={[0, 1]}
            ticks={[0, 0.25, 0.5, 0.75, 1]}
            stroke={AXIS}
            tick={{ fill: MUTED, fontSize: 12 }}
            width={40}
          />
          <Tooltip content={(props) => <ChartTooltip {...props} />} />
          {(hasActual || hasBand) && <Legend wrapperStyle={{ fontSize: 12 }} />}
          {hasBand && (
            <Area
              dataKey="bandLow"
              stackId="band"
              stroke="none"
              fill="transparent"
              isAnimationActive={false}
              legendType="none"
              tooltipType="none"
            />
          )}
          {hasBand && (
            <Area
              dataKey="bandRange"
              name="Интервал P10–P90"
              stackId="band"
              stroke="none"
              fill={COLOR_PREDICTED}
              fillOpacity={0.12}
              isAnimationActive={false}
              tooltipType="none"
            />
          )}
          <Line
            type="monotone"
            dataKey="predictedPower"
            name="Прогноз"
            stroke={COLOR_PREDICTED}
            strokeWidth={2}
            dot={false}
            activeDot={{ r: 5, strokeWidth: 2, stroke: SURFACE }}
            isAnimationActive={false}
          />
          {hasActual && (
            <Line
              type="monotone"
              dataKey="actualPower"
              name="Факт"
              stroke={COLOR_ACTUAL}
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 5, strokeWidth: 2, stroke: SURFACE }}
              connectNulls={false}
              isAnimationActive={false}
            />
          )}
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}
