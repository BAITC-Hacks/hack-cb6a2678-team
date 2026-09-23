import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { completeSiteTour } from '../siteTourStorage'

const steps = [
  {
    target: '[data-tour="turbine"]',
    title: 'Выберите турбину',
    body: 'Нажмите на название турбины. Прогноз, показатели и журнал агента обновятся для выбранной установки. Турбину также можно выбрать на карте.',
  },
  {
    target: '[data-tour="date"]',
    title: 'Укажите дату выпуска',
    body: 'Откройте список дат и выберите день выпуска прогноза. Прогноз на графике относится к следующим суткам или двум суткам — в зависимости от горизонта.',
  },
  {
    target: '[data-tour="horizon"]',
    title: 'Задайте горизонт',
    body: 'Нажмите «24ч» или «48ч», чтобы посмотреть прогноз на одни или двое суток.',
  },
  {
    target: '[data-tour="forecast"]',
    title: 'Изучите прогноз',
    body: 'Здесь показаны почасовой график, сводка, предупреждения и версии прогноза. Переключатель «Показать факт» доступен, когда для этих часов есть фактическая выработка.',
  },
  {
    target: '[data-tour="metrics"]',
    title: 'Проверьте качество',
    body: 'Здесь собраны показатели точности на отложенном тесте. Они помогают оценить качество модели для выбранной турбины.',
  },
  {
    target: '[data-tour="agent"]',
    title: 'Посмотрите работу агента',
    body: 'Журнал показывает этапы цикла и их статус. Кнопка «Запустить новый цикл» запускает его вручную, а часы позволяют выбрать плановый цикл.',
  },
  {
    target: '[data-tour="chat"]',
    title: 'Задайте вопрос агенту',
    body: 'Введите вопрос о прогнозе в поле чата и нажмите «Спросить». Ответ появится выше вместе с использованными инструментами.',
  },
] as const

interface Props {
  onClose: () => void
}

type Spotlight = { top: number; left: number; right: number; bottom: number }

export function SiteTour({ onClose }: Props) {
  const [index, setIndex] = useState(-1)
  const [spotlight, setSpotlight] = useState<Spotlight | null>(null)
  const [closing, setClosing] = useState(false)
  const dialogRef = useRef<HTMLDivElement>(null)
  const closeTimer = useRef<number | null>(null)

  useEffect(() => () => {
    if (closeTimer.current !== null) window.clearTimeout(closeTimer.current)
  }, [])

  useEffect(() => {
    if (index < 0) return
    const target = document.querySelector<HTMLElement>(steps[index].target)
    if (!target) return

    target.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' })
    const update = () => {
      const rect = target.getBoundingClientRect()
      const gap = 6
      setSpotlight({
        top: Math.max(0, rect.top - gap),
        left: Math.max(0, rect.left - gap),
        right: Math.min(window.innerWidth, rect.right + gap),
        bottom: Math.min(window.innerHeight, rect.bottom + gap),
      })
    }
    update()
    const observer = new ResizeObserver(update)
    observer.observe(target)
    window.addEventListener('resize', update)
    window.addEventListener('scroll', update, true)
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', update)
      window.removeEventListener('scroll', update, true)
    }
  }, [index])

  useEffect(() => {
    dialogRef.current?.focus()
  }, [index])

  const close = () => {
    if (closing) return
    setClosing(true)
    const duration = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 220
    closeTimer.current = window.setTimeout(() => {
      completeSiteTour()
      onClose()
    }, duration)
  }

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        close()
      }
      if (event.key !== 'Tab' || !dialogRef.current) return
      const buttons = Array.from(dialogRef.current.querySelectorAll<HTMLButtonElement>('button'))
      if (buttons.length === 0) return
      const first = buttons[0]
      const last = buttons[buttons.length - 1]
      if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (document.activeElement === last || !dialogRef.current.contains(document.activeElement))) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  })

  const intro = index < 0
  const step = intro ? null : steps[index]
  const dialogStyle = !intro && spotlight ? {
    top: spotlight.bottom + 300 < window.innerHeight ? spotlight.bottom + 14 : undefined,
    bottom: spotlight.bottom + 300 < window.innerHeight ? undefined : Math.max(16, window.innerHeight - spotlight.top + 14),
    left: Math.min(Math.max(16, spotlight.left), Math.max(16, window.innerWidth - 416)),
  } : undefined

  return createPortal(
    <div className={`site-tour${closing ? ' site-tour-closing' : ''}`} aria-label="Обучение работе с сайтом">
      <div className={`site-tour-shade site-tour-full-shade${intro || !spotlight ? '' : ' site-tour-full-shade-hidden'}`} />
      {!intro && spotlight && (
        <>
          <div className="site-tour-shade" style={{ top: 0, left: 0, right: 0, height: spotlight.top }} />
          <div className="site-tour-shade" style={{ top: spotlight.bottom, left: 0, right: 0, bottom: 0 }} />
          <div className="site-tour-shade" style={{ top: spotlight.top, left: 0, width: spotlight.left, height: spotlight.bottom - spotlight.top }} />
          <div className="site-tour-shade" style={{ top: spotlight.top, left: spotlight.right, right: 0, height: spotlight.bottom - spotlight.top }} />
          <div className="site-tour-highlight" style={{ top: spotlight.top, left: spotlight.left, width: spotlight.right - spotlight.left, height: spotlight.bottom - spotlight.top }} />
        </>
      )}
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="site-tour-title"
        tabIndex={-1}
        className={`site-tour-dialog${intro || !spotlight ? ' site-tour-intro' : ''}`}
        style={dialogStyle}
      >
        <div key={index} className="site-tour-copy">
          <div className="site-tour-kicker">{intro ? 'Добро пожаловать' : `Шаг ${index + 1} из ${steps.length}`}</div>
          <h2 id="site-tour-title">{intro ? 'Как пользоваться WES Forecast Dashboard' : step?.title}</h2>
          {intro ? (
            <>
              <p>Сайт помогает изучать прогноз выработки ветроэлектростанции и работу агента.</p>
              <ul>
                <li>Выбирайте турбину, дату и горизонт прогноза.</li>
                <li>Смотрите почасовой график, версии, предупреждения и точность.</li>
                <li>Изучайте журнал агента и задавайте ему вопросы в чате.</li>
              </ul>
              <p>Далее мы покажем, где находится каждая функция.</p>
            </>
          ) : <p>{step?.body}</p>}
        </div>
        <div className="site-tour-actions">
          <button type="button" className="site-tour-skip" onClick={close}>Пропустить всё</button>
          <div className="site-tour-navigation">
            {!intro && <button type="button" className="site-tour-back" onClick={() => setIndex(index - 1)}>Назад</button>}
            <button type="button" className="site-tour-next" onClick={index === steps.length - 1 ? close : () => setIndex(index + 1)}>
              {index === steps.length - 1 ? 'Готово' : intro ? 'Начать' : 'Далее'}
            </button>
          </div>
        </div>
      </div>
    </div>,
    document.body,
  )
}
