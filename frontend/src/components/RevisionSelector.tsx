import type { ForecastRevision } from '../types'

interface Props {
  revisions: ForecastRevision[]
  value: number
  onChange: (revision: number) => void
}

function hourLabel(ts: string): string {
  return `${new Date(ts).getUTCHours().toString().padStart(2, '0')} UTC`
}

export function RevisionSelector({ revisions, value, onChange }: Props) {
  if (revisions.length === 0) return null

  return (
    <div className="revision-row">
      {revisions.map((r) => (
        <button
          key={r.revision}
          type="button"
          className={r.revision === value ? 'revision-btn active' : 'revision-btn'}
          onClick={() => onChange(r.revision)}
        >
          <span className="revision-btn-time">{hourLabel(r.forecastIssuedAt)}</span>
          {r.changeVsPreviousPct !== null && (
            <span className="revision-btn-delta">
              {r.changeVsPreviousPct >= 0 ? '+' : ''}
              {r.changeVsPreviousPct.toFixed(0)}%
            </span>
          )}
        </button>
      ))}
    </div>
  )
}
