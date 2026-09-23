interface Props {
  value: string
  onChange: (date: string) => void
}

// Backtest-период: 1-28 февраля 2026
const DATES = Array.from({ length: 28 }, (_, i) => `2026-02-${String(i + 1).padStart(2, '0')}`)

export function DateSelector({ value, onChange }: Props) {
  return (
    <select className="date-selector" value={value} onChange={(e) => onChange(e.target.value)}>
      {DATES.map((d) => (
        <option key={d} value={d}>
          {d}
        </option>
      ))}
    </select>
  )
}
