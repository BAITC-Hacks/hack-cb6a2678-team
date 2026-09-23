import type { HorizonHours } from '../types'

interface Props {
  value: HorizonHours
  onChange: (horizonHours: HorizonHours) => void
}

const OPTIONS: HorizonHours[] = [24, 48]

export function HorizonSelector({ value, onChange }: Props) {
  return (
    <div className="selector">
      {OPTIONS.map((h) => (
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
