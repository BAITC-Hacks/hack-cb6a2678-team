export interface Turbine {
  id: string
  name: string
  lat: number
  lon: number
}

export type HorizonHours = 24 | 48

export interface MetaResponse {
  backtestFrom: string
  backtestTo: string
  horizons: HorizonHours[]
  revisionsPerDay: number
  timezone: string
  issueTimeLocal: string
  localTz: string
  metricsFrom: string | null
  metricsTo: string | null
  modelVersion: string
  llmModel: string
}

export interface ForecastPoint {
  timestamp: string
  predictedPower: number
  p10: number | null
  p90: number | null
  actualPower: number | null
  windSpeed: number | null
  temperature: number | null
}

export interface ForecastSummary {
  meanPower: number
  minPower: number
  maxPower: number
  maxPowerAt: string
  fullLoadHours: number
  lowPowerHours: number
}

export type AlertType =
  | 'icing'
  | 'storm_cutout'
  | 'ramp_down'
  | 'ramp_up'
  | 'low_confidence'
  | 'model_physics_gap'
export type AlertSeverity = 'info' | 'warning' | 'critical'

export interface ForecastAlert {
  type: AlertType
  severity: AlertSeverity
  from: string
  to: string
  message: string
}

export interface ForecastResponse {
  turbineId: string
  forecastIssuedAt: string
  revision: number
  horizonHours: HorizonHours
  weatherSource: string
  weatherIssuedAt: string
  modelVersion: string
  summary: ForecastSummary
  alerts: ForecastAlert[]
  agentReport: string | null
  points: ForecastPoint[]
}

export interface ForecastRevision {
  revision: number
  forecastIssuedAt: string
  weatherIssuedAt: string
  meanPower: number
  changeVsPreviousPct: number | null
}

export interface ForecastRevisionsResponse {
  turbineId: string
  date: string
  revisions: ForecastRevision[]
}

export interface RunForecastRequest {
  turbineId: string
  date: string
  horizonHours?: HorizonHours
}

export interface RunForecastResponse {
  cycleId: string
  status: string
}

export interface DailyMetric {
  date: string
  mae: number
  rmse: number
}

export interface LeadTimeMetric {
  leadHour: number
  mae: number
}

export interface MetricsResponse {
  turbineId?: string | null
  periodFrom: string
  periodTo: string
  mae: number
  rmse: number
  mape: number
  baselineMae: number | null
  powerCurveBaselineMae: number | null
  intervalCoverage: number | null
  bias: number | null
  sampleHours: number
  evaluationModelVersion: string | null
  evaluationSource: string | null
  byDay: DailyMetric[]
  byLeadTime: LeadTimeMetric[]
}

export type AgentStepStatus = 'success' | 'failed' | 'retrying' | 'running'

export interface AgentStep {
  stepName: string
  status: AgentStepStatus
  timestamp: string
  details: string | null
  tool: string | null
  dataIssuedAt: string | null
}

export interface AgentLogResponse {
  date: string
  cycleId: string
  revision: number
  forecastIssuedAt: string
  steps: AgentStep[]
}

export interface AgentChatRequest {
  message: string
}

export interface AgentToolCall {
  tool: string
  args: Record<string, unknown>
  ok: boolean
  error: string | null
}

export interface AgentChatResponse {
  answer: string
  model: string
  toolCalls: AgentToolCall[]
  durationMs: number
}

export interface ErrorResponse {
  message: string
}
