import { useEffect, useRef, useState } from 'react'
import { getForecast, getForecastRevisions, getMeta, getMetrics, getTurbines } from './api'
import { AgentLogPanel } from './components/AgentLogPanel'
import { AlertsList } from './components/AlertsList'
import { ChatPanel } from './components/ChatPanel'
import { DateSelector } from './components/DateSelector'
import { ForecastChart } from './components/ForecastChart'
import { ForecastMeta } from './components/ForecastMeta'
import { ForecastSummaryTiles } from './components/ForecastSummaryTiles'
import { HorizonSelector } from './components/HorizonSelector'
import { MetricsCards } from './components/MetricsCards'
import { RevisionSelector } from './components/RevisionSelector'
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
import './App.css'

const FALLBACK_FROM = '2026-01-31'
const FALLBACK_TO = '2026-02-27'

function App() {
  const [meta, setMeta] = useState<MetaResponse | null>(null)
  const [turbines, setTurbines] = useState<Turbine[]>([])
  const [turbineId, setTurbineId] = useState('t1')
  const [date, setDate] = useState('2026-02-01')
  const [horizonHours, setHorizonHours] = useState<HorizonHours>(48)
  const [revision, setRevision] = useState(1)
  const [showActual, setShowActual] = useState(false)

  const [forecast, setForecast] = useState<ForecastResponse | null>(null)
  const [revisions, setRevisions] = useState<ForecastRevision[]>([])
  const [metrics, setMetrics] = useState<MetricsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  const forecastSectionRef = useRef<HTMLElement | null>(null)

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
    getForecast(turbineId, date, horizonHours, revision)
      .then(setForecast)
      .catch((e: Error) => setError(e.message))
  }, [turbineId, date, horizonHours, revision])

  useEffect(() => {
    getForecastRevisions(turbineId, date)
      .then((res) => setRevisions(res.revisions))
      .catch((e: Error) => setError(e.message))
  }, [turbineId, date])

  useEffect(() => {
    const from = meta?.backtestFrom ?? FALLBACK_FROM
    const to = meta?.backtestTo ?? FALLBACK_TO
    getMetrics(from, to, turbineId)
      .then(setMetrics)
      .catch((e: Error) => setError(e.message))
  }, [turbineId, meta])

  const handleDateChange = (d: string) => {
    setDate(d)
    setRevision(1)
  }

  const handleTurbineChange = (id: string) => {
    setTurbineId(id)
    setRevision(1)
  }

  const handleSelectDateFromCalendar = (d: string) => {
    handleDateChange(d)
    forecastSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-title">
          <h1>WES Forecast Dashboard</h1>
          <span>Agentic AI-прогноз выработки ВЭС</span>
        </div>
        <div className="filters">
          <TurbineSelector turbines={turbines} value={turbineId} onChange={handleTurbineChange} />
          <DateSelector
            value={date}
            onChange={handleDateChange}
            from={meta?.backtestFrom ?? FALLBACK_FROM}
            to={meta?.backtestTo ?? FALLBACK_TO}
          />
          <HorizonSelector value={horizonHours} onChange={setHorizonHours} options={meta?.horizons ?? [24, 48]} />
        </div>
      </header>

      {error && <div className="error">Ошибка: {error}</div>}

      <div className="bento-grid">
        <section className="bento-tile bento-tile-map">
          <div className="section-header">
            <h2>Расположение турбин</h2>
          </div>
          <div className="bento-tile-content">
            <TurbineMap turbines={turbines} selectedTurbineId={turbineId} onSelect={handleTurbineChange} />
          </div>
        </section>

        <section className="bento-tile bento-tile-forecast" ref={forecastSectionRef}>
          <div className="section-header">
            <h2>Почасовой прогноз выработки</h2>
          </div>
          <div className="bento-tile-content">
            <RevisionSelector revisions={revisions} value={revision} onChange={setRevision} />

            {forecast ? (
              <>
                <div className="forecast-toolbar">
                  <ForecastMeta forecast={forecast} />
                  <label className="show-actual-toggle">
                    <input
                      type="checkbox"
                      checked={showActual}
                      onChange={(e) => setShowActual(e.target.checked)}
                    />
                    Показать факт
                  </label>
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
          <div className="section-header">
            <h2>
              Качество · {meta?.backtestFrom ?? FALLBACK_FROM} – {meta?.backtestTo ?? FALLBACK_TO}
            </h2>
          </div>
          <div className="bento-tile-content">
            <MetricsCards metrics={metrics} onSelectDate={handleSelectDateFromCalendar} />
          </div>
        </section>

        <section className="bento-tile bento-tile-agent">
          <div className="section-header">
            <h2>Agent trace</h2>
          </div>
          <div className="bento-tile-content">
            <AgentLogPanel turbineId={turbineId} date={date} horizonHours={horizonHours} revision={revision} />
          </div>
        </section>

        <section className="bento-tile bento-tile-chat">
          <div className="section-header">
            <h2>Чат с агентом</h2>
          </div>
          <div className="bento-tile-content">
            <ChatPanel />
          </div>
        </section>
      </div>
    </div>
  )
}

export default App
