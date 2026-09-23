import { useEffect, useState } from 'react'
import { getForecast, getMetrics, getTurbines } from './api'
import { AgentLogPanel } from './components/AgentLogPanel'
import { DateSelector } from './components/DateSelector'
import { ForecastChart } from './components/ForecastChart'
import { HorizonSelector } from './components/HorizonSelector'
import { MetricsCards } from './components/MetricsCards'
import { TurbineMap } from './components/TurbineMap'
import { TurbineSelector } from './components/TurbineSelector'
import type { ForecastResponse, HorizonHours, MetricsResponse, Turbine } from './types'
import './App.css'

const METRICS_FROM = '2026-02-01'
const METRICS_TO = '2026-02-28'

function App() {
  const [turbines, setTurbines] = useState<Turbine[]>([])
  const [turbineId, setTurbineId] = useState('t1')
  const [date, setDate] = useState('2026-02-01')
  const [horizonHours, setHorizonHours] = useState<HorizonHours>(48)

  const [forecast, setForecast] = useState<ForecastResponse | null>(null)
  const [metrics, setMetrics] = useState<MetricsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

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
    getMetrics(METRICS_FROM, METRICS_TO, turbineId)
      .then(setMetrics)
      .catch((e: Error) => setError(e.message))
  }, [turbineId])

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-title">
          <h1>Панель прогнозов ВЭС</h1>
          <span>Agentic AI-прогноз выработки ВЭС</span>
        </div>
        <div className="filters">
          <TurbineSelector turbines={turbines} value={turbineId} onChange={setTurbineId} />
          <DateSelector value={date} onChange={setDate} />
          <HorizonSelector value={horizonHours} onChange={setHorizonHours} />
        </div>
      </header>

      {error && <div className="error">Ошибка: {error}</div>}

      <section className="section">
        <div className="section-header">
          <h2>Расположение турбин</h2>
        </div>
        <TurbineMap turbines={turbines} selectedTurbineId={turbineId} onSelect={setTurbineId} />
      </section>

      <section className="section">
        <div className="section-header">
          <h2>Почасовой прогноз выработки</h2>
        </div>
        {forecast ? (
          <ForecastChart points={forecast.points} />
        ) : (
          <div className="card muted">Загрузка прогноза...</div>
        )}
      </section>

      <section className="section">
        <div className="section-header">
          <h2>Метрики качества · 1–28 февраля 2026</h2>
        </div>
        <MetricsCards metrics={metrics} />
      </section>

      <section className="section">
        <div className="section-header">
          <h2>Agent trace</h2>
        </div>
        <AgentLogPanel turbineId={turbineId} date={date} horizonHours={horizonHours} />
      </section>
    </div>
  )
}

export default App
