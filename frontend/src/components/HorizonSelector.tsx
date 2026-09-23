import type { HorizonHours } from '../types'

interface Props {
  value: HorizonHours
  onChange: (horizonHours: HorizonHours) => void
  options: HorizonHours[]
}

export function HorizonSelector({ value, onChange, options }: Props) {
  return (
    <div className="selector">
      {options.map((h) => (
        <button
          key={h}
          type="button"
          className={h === value ? 'selector-btn active' : 'selector-btn'}
          onClick={() => onChange(h)}
        >
          {h}ч
        </button>
      ))}
    </div>
  )
}
