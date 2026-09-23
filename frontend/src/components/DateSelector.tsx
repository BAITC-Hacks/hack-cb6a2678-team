interface Props {
  value: string
  onChange: (date: string) => void
  from: string
  to: string
}

function dateRange(from: string, to: string): string[] {
  const dates: string[] = []
  const cursor = new Date(`${from}T00:00:00Z`)
  const end = new Date(`${to}T00:00:00Z`)
  while (cursor.getTime() <= end.getTime()) {
    dates.push(cursor.toISOString().slice(0, 10))
    cursor.setUTCDate(cursor.getUTCDate() + 1)
  }
  return dates
}

export function DateSelector({ value, onChange, from, to }: Props) {
  const dates = dateRange(from, to)
  return (
    <select className="date-selector" value={value} onChange={(e) => onChange(e.target.value)}>
      {dates.map((d) => (
        <option key={d} value={d}>
          {d}
        </option>
      ))}
    </select>
  )
}
