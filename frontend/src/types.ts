export interface Turbine {
  id: string
  name: string
  lat: number
  lon: number
}

export interface ForecastPoint {
  timestamp: string
  predictedPower: number
  actualPower: number | null
  windSpeed: number
  temperature: number
}

export type HorizonHours = 24 | 48

export interface ForecastResponse {
  turbineId: string
  forecastIssuedAt: string
  horizonHours: HorizonHours
  points: ForecastPoint[]
}

export interface DailyMetric {
  date: string
  mae: number
  rmse: number
}

export interface MetricsResponse {
  turbineId?: string | null
  periodFrom: string
  periodTo: string
  mae: number
  rmse: number
  mape: number
  baselineMae: number
  byDay: DailyMetric[]
}

export type AgentStepStatus = 'success' | 'failed' | 'retrying' | 'running'

export interface AgentStep {
  stepName: string
  status: AgentStepStatus
  timestamp: string
  details: string | null
}

export interface AgentLogResponse {
  date: string
  cycleId: string
  steps: AgentStep[]
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

export interface ErrorResponse {
  message: string
}
