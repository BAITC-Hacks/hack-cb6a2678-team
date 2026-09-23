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
  revision = 1,
): Promise<ForecastResponse> {
  const params = new URLSearchParams({
    turbineId,
    date,
    horizonHours: String(horizonHours),
    revision: String(revision),
  })
  return fetch(`${BASE}/forecast?${params}`).then((res) => handleResponse<ForecastResponse>(res))
}

export function getForecastRevisions(turbineId: string, date: string): Promise<ForecastRevisionsResponse> {
  const params = new URLSearchParams({ turbineId, date })
  return fetch(`${BASE}/forecast/revisions?${params}`).then((res) =>
    handleResponse<ForecastRevisionsResponse>(res),
  )
}

export function getMetrics(
  from: string,
  to: string,
  turbineId?: string,
): Promise<MetricsResponse> {
  const params = new URLSearchParams({ from, to })
  if (turbineId) params.set('turbineId', turbineId)
  return fetch(`${BASE}/metrics?${params}`).then((res) => handleResponse<MetricsResponse>(res))
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
