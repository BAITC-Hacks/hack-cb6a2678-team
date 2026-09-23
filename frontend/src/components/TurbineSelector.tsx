import type { Turbine } from '../types'

interface Props {
  turbines: Turbine[]
  value: string
  onChange: (turbineId: string) => void
}

export function TurbineSelector({ turbines, value, onChange }: Props) {
  return (
    <div className="selector">
      {turbines.map((t) => (
        <button
          key={t.id}
          type="button"
          className={t.id === value ? 'selector-btn active' : 'selector-btn'}
          onClick={() => onChange(t.id)}
        >
          {t.name}
        </button>
      ))}
    </div>
  )
}
