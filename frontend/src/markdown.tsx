import type { ReactNode } from 'react'

const INLINE_PATTERN = /`([^`]+)`|\*\*([^*]+)\*\*|__([^_]+)__|\*([^*]+)\*|_([^_]+)_/

function renderInline(text: string): ReactNode[] {
  const parts: ReactNode[] = []
  let remaining = text
  let key = 0

  while (remaining.length > 0) {
    const match = INLINE_PATTERN.exec(remaining)
    if (!match) {
      parts.push(remaining)
      break
    }
    if (match.index > 0) parts.push(remaining.slice(0, match.index))

    if (match[1] !== undefined) parts.push(<code key={key++}>{match[1]}</code>)
    else if (match[2] !== undefined) parts.push(<strong key={key++}>{match[2]}</strong>)
    else if (match[3] !== undefined) parts.push(<strong key={key++}>{match[3]}</strong>)
    else if (match[4] !== undefined) parts.push(<em key={key++}>{match[4]}</em>)
    else if (match[5] !== undefined) parts.push(<em key={key++}>{match[5]}</em>)

    remaining = remaining.slice(match.index + match[0].length)
  }

  return parts
}

// Минимальный, безопасный markdown → JSX (без сторонней библиотеки и без
// dangerouslySetInnerHTML): заголовки, списки, параграфы, **жирный**/*курсив*/`код`.
export function renderMarkdown(text: string): ReactNode {
  const lines = text.split('\n')
  const blocks: ReactNode[] = []
  let i = 0
  let key = 0

  while (i < lines.length) {
    const line = lines[i]

    if (line.trim() === '') {
      i++
      continue
    }

    if (/^#{1,6}\s+/.test(line)) {
      const content = line.replace(/^#{1,6}\s+/, '')
      blocks.push(
        <p key={key++} className="md-heading">
          {renderInline(content)}
        </p>,
      )
      i++
      continue
    }

    if (/^[-*]\s+/.test(line)) {
      const items: string[] = []
      while (i < lines.length && /^[-*]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^[-*]\s+/, ''))
        i++
      }
      blocks.push(
        <ul key={key++} className="md-list">
          {items.map((item, idx) => (
            <li key={idx}>{renderInline(item)}</li>
          ))}
        </ul>,
      )
      continue
    }

    if (/^\d+\.\s+/.test(line)) {
      const items: string[] = []
      while (i < lines.length && /^\d+\.\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\d+\.\s+/, ''))
        i++
      }
      blocks.push(
        <ol key={key++} className="md-list">
          {items.map((item, idx) => (
            <li key={idx}>{renderInline(item)}</li>
          ))}
        </ol>,
      )
      continue
    }

    const paraLines: string[] = []
    while (
      i < lines.length &&
      lines[i].trim() !== '' &&
      !/^[-*]\s+/.test(lines[i]) &&
      !/^\d+\.\s+/.test(lines[i]) &&
      !/^#{1,6}\s+/.test(lines[i])
    ) {
      paraLines.push(lines[i])
      i++
    }
    blocks.push(
      <p key={key++} className="md-p">
        {renderInline(paraLines.join(' '))}
      </p>,
    )
  }

  return blocks
}
