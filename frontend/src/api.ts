import type {
  ChaosState, DashboardData, InvestigationEvent, LedgerEntry, LiveAlert, ModeInfo, Topology, TriageReport,
} from './types'

async function get<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`${url} -> ${res.status}`)
  return res.json()
}

/** POST and surface the server's {error} message when there is one. */
async function post<T>(url: string, body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    let message = `${url} -> ${res.status}`
    try {
      const data = await res.json() as { error?: string; message?: string }
      message = data.error ?? data.message ?? message
    } catch { /* not JSON */ }
    throw new Error(message)
  }
  return res.json() as Promise<T>
}

export const api = {
  topology: () => get<Topology>('/api/topology'),
  mode: () => get<ModeInfo>('/api/mode'),
  alerts: () => get<LiveAlert[]>('/api/alerts'),

  chaosState: () => get<ChaosState>('/api/chaos'),
  injectFault: (id: string) => post<{ active: string }>(`/api/chaos/${id}`),
  resetFaults: () => post<{ active: string }>('/api/chaos/none'),

  startIncident: (alertText: string) =>
    post<{ incidentId: string; mode: string; scenarioId: string; estate: string; model: string | null }>(
      '/api/incidents', { alertText }),

  incident: (id: string) => get<{ status: string; error: string | null }>(`/api/incidents/${id}`),

  analytics: () => get<DashboardData>('/api/analytics'),

  report: (id: string) => get<TriageReport>(`/api/incidents/${id}/report`),
  ledger: (id: string) => get<LedgerEntry[]>(`/api/incidents/${id}/ledger`),
  events: (id: string) => get<InvestigationEvent[]>(`/api/incidents/${id}/events`),
}
