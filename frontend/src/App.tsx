import { useEffect, useState } from 'react'
import { getForecast, getForecastRevisions, getMeta, getMetrics, getTurbines } from './api'
import { AgentLogPanel } from './components/AgentLogPanel'
import { AlertsList } from './components/AlertsList'
import { ChatPanel } from './components/ChatPanel'
import { DateSelector } from './components/DateSelector'
import { ForecastChart } from './components/ForecastChart'
import { ForecastMeta } from './components/ForecastMeta'
import { ForecastSummaryTiles } from './components/ForecastSummaryTiles'
import { ForecastVersions } from './components/ForecastVersions'
import { HorizonSelector } from './components/HorizonSelector'
import { MetricsCards } from './components/MetricsCards'
import { SiteTour } from './components/SiteTour'
import { TurbineMap } from './components/TurbineMap'
import { TurbineSelector } from './components/TurbineSelector'
import type {
  ForecastResponse,
  ForecastRevision,
  HorizonHours,
  MetaResponse,
  MetricsResponse,
  Turbine,
} from './types'
import { hasCompletedSiteTour } from './siteTourStorage'
import './App.css'

const FALLBACK_FROM = '2026-01-31'
const FALLBACK_TO = '2026-02-26'

function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

function App() {
  const [meta, setMeta] = useState<MetaResponse | null>(null)
  const [turbines, setTurbines] = useState<Turbine[]>([])
  const [turbineId, setTurbineId] = useState('t1')
  const [date, setDate] = useState('2026-02-01')
  const [horizonHours, setHorizonHours] = useState<HorizonHours>(48)
  const [showActual, setShowActual] = useState(false)
  const [showTour, setShowTour] = useState(() => !hasCompletedSiteTour())

  const [forecast, setForecast] = useState<ForecastResponse | null>(null)
  const [revisions, setRevisions] = useState<ForecastRevision[]>([])
  const [metrics, setMetrics] = useState<MetricsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  // v1.2: /forecast/revisions хочет целевые сутки, а не день выпуска. Прогноз, выпущенный
  // в `date`, покрывает сутки date+1/date+2 — показываем версии для первых из них (date+1).
  const revisionsTargetDate = addDays(date, 1)
  const actualAvailable = forecast?.points.some((point) => point.actualPower !== null) ?? false

  useEffect(() => {
    getMeta()
      .then(setMeta)
      .catch((e: Error) => setError(e.message))
  }, [])

  useEffect(() => {
    getTurbines()
      .then(setTurbines)
      .catch((e: Error) => setError(e.message))
  }, [])

  useEffect(() => {
    getForecast(turbineId, date, horizonHours)
      .then(setForecast)
      .catch((e: Error) => setError(e.message))
  }, [turbineId, date, horizonHours])

  useEffect(() => {
    getForecastRevisions(turbineId, revisionsTargetDate)
      .then((res) => setRevisions(res.revisions))
      .catch((e: Error) => setError(e.message))
  }, [turbineId, revisionsTargetDate])

  useEffect(() => {
    // v1.2: без from/to бэкенд сам берёт весь период отложенного теста (январь —
    // факта за февраль нет ни у кого, см. MetaResponse.metricsFrom/metricsTo).
    getMetrics(turbineId)
      .then(setMetrics)
      .catch((e: Error) => setError(e.message))
  }, [turbineId])

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-title">
          <h1>WES Forecast Dashboard</h1>
          <span>Agentic AI-прогноз выработки ВЭС</span>
        </div>
        <button type="button" className="tour-open-btn" onClick={() => setShowTour(true)}>
          Как пользоваться
        </button>
        <div className="filters">
          <div data-tour="turbine">
            <TurbineSelector turbines={turbines} value={turbineId} onChange={setTurbineId} />
          </div>
          <div data-tour="date">
            <DateSelector
              value={date}
              onChange={setDate}
              from={meta?.backtestFrom ?? FALLBACK_FROM}
              to={meta?.backtestTo ?? FALLBACK_TO}
            />
          </div>
          <div data-tour="horizon">
            <HorizonSelector value={horizonHours} onChange={setHorizonHours} options={meta?.horizons ?? [24, 48]} />
          </div>
        </div>
      </header>

      {error && <div className="error">Ошибка: {error}</div>}

      <div className="bento-grid">
        <section className="bento-tile bento-tile-map">
          <div className="section-header">
            <h2>Расположение турбин</h2>
          </div>
          <div className="bento-tile-content">
            <TurbineMap turbines={turbines} selectedTurbineId={turbineId} onSelect={setTurbineId} />
          </div>
        </section>

        <section className="bento-tile bento-tile-forecast">
          <div className="section-header" data-tour="forecast">
            <h2>Почасовой прогноз выработки</h2>
          </div>
          <div className="bento-tile-content">
            <ForecastVersions targetDate={revisionsTargetDate} revisions={revisions} />

            {forecast ? (
              <>
                <div className="forecast-toolbar">
                  <ForecastMeta
                    forecast={forecast}
                    issueTimeLocal={meta?.issueTimeLocal ?? null}
                    localTz={meta?.localTz ?? null}
                  />
                  <div className="actual-control">
                    <label className={`show-actual-toggle${actualAvailable ? '' : ' show-actual-toggle-disabled'}`}>
                      <input
                        type="checkbox"
                        checked={actualAvailable && showActual}
                        disabled={!actualAvailable}
                        aria-describedby={actualAvailable ? undefined : 'actual-unavailable'}
                        onChange={(e) => setShowActual(e.target.checked)}
                      />
                      Показать факт
                    </label>
                    {!actualAvailable && (
                      <span id="actual-unavailable" className="actual-unavailable">
                        Факт за выбранные часы отсутствует в данных
                      </span>
                    )}
                  </div>
                </div>
                <ForecastChart points={forecast.points} showActual={showActual} />
                <ForecastSummaryTiles summary={forecast.summary} />
                <AlertsList alerts={forecast.alerts} />
                {forecast.agentReport && <div className="agent-report">{forecast.agentReport}</div>}
              </>
            ) : (
              <div className="muted">Загрузка прогноза...</div>
            )}
          </div>
        </section>

        <section className="bento-tile bento-tile-metrics">
          <div className="section-header" data-tour="metrics">
            <h2>
              Качество на отложенном тесте · январь 2026
            </h2>
          </div>
          <div className="bento-tile-content">
            <MetricsCards metrics={metrics} />
          </div>
        </section>

        <section className="bento-tile bento-tile-agent">
          <div className="section-header" data-tour="agent">
            <h2>Agent trace</h2>
          </div>
          <div className="bento-tile-content">
            <AgentLogPanel key={`${turbineId}:${date}`} turbineId={turbineId} date={date} horizonHours={horizonHours} />
          </div>
        </section>

        <section className="bento-tile bento-tile-chat">
          <div className="section-header" data-tour="chat">
            <h2>Чат с агентом</h2>
          </div>
          <div className="bento-tile-content">
            <ChatPanel />
          </div>
        </section>
      </div>
      {showTour && <SiteTour onClose={() => setShowTour(false)} />}
    </div>
  )
}

export default App
