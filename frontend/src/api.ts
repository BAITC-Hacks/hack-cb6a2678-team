import type {
  AgentChatRequest,
  AgentChatResponse,
  AgentLogResponse,
  ErrorResponse,
  ForecastResponse,
  ForecastRevisionsResponse,
  HorizonHours,
  MetaResponse,
  MetricsResponse,
  RunForecastRequest,
  RunForecastResponse,
  Turbine,
} from './types'

const BASE = '/api'

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as ErrorResponse | null
    throw new Error(body?.message ?? `${res.status} ${res.statusText}`)
  }
  return res.json() as Promise<T>
}

export function getMeta(): Promise<MetaResponse> {
  return fetch(`${BASE}/meta`).then((res) => handleResponse<MetaResponse>(res))
}

export function getTurbines(): Promise<Turbine[]> {
  return fetch(`${BASE}/turbines`).then((res) => handleResponse<Turbine[]>(res))
}

export function getForecast(
  turbineId: string,
  date: string,
  horizonHours: HorizonHours = 48,
): Promise<ForecastResponse> {
  const params = new URLSearchParams({ turbineId, date, horizonHours: String(horizonHours) })
  return fetch(`${BASE}/forecast?${params}`).then((res) => handleResponse<ForecastResponse>(res))
}

// v1.2: date здесь — целевые сутки прогноза (день, для которого хотим увидеть версии),
// а не день выпуска. См. описание /api/forecast/revisions в openapi.yaml.
export function getForecastRevisions(turbineId: string, targetDate: string): Promise<ForecastRevisionsResponse> {
  const params = new URLSearchParams({ turbineId, date: targetDate })
  return fetch(`${BASE}/forecast/revisions?${params}`).then((res) =>
    handleResponse<ForecastRevisionsResponse>(res),
  )
}

// v1.2: from/to необязательны — без них бэкенд берёт весь период отложенного
// теста (см. MetaResponse.metricsFrom/metricsTo).
export function getMetrics(turbineId?: string, from?: string, to?: string): Promise<MetricsResponse> {
  const params = new URLSearchParams()
  if (turbineId) params.set('turbineId', turbineId)
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  const query = params.toString()
  return fetch(`${BASE}/metrics${query ? `?${query}` : ''}`).then((res) => handleResponse<MetricsResponse>(res))
}

export function getAgentLog(date: string, turbineId?: string, revision = 1): Promise<AgentLogResponse> {
  const params = new URLSearchParams({ date, revision: String(revision) })
  if (turbineId) params.set('turbineId', turbineId)
  return fetch(`${BASE}/agent-log?${params}`).then((res) => handleResponse<AgentLogResponse>(res))
}

export function runForecastCycle(body: RunForecastRequest): Promise<RunForecastResponse> {
  return fetch(`${BASE}/forecast/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then((res) => handleResponse<RunForecastResponse>(res))
}

export function agentChat(body: AgentChatRequest): Promise<AgentChatResponse> {
  return fetch(`${BASE}/agent/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then((res) => handleResponse<AgentChatResponse>(res))
}
