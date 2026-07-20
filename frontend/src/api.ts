import type { InvestigationEvent, LedgerEntry, ScenarioInfo, Topology, TriageReport } from './types'

async function get<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`${url} -> ${res.status}`)
  return res.json()
}

export const api = {
  topology: () => get<Topology>('/api/topology'),

  chaosState: () => get<{ scenarios: ScenarioInfo[]; active: string }>('/api/chaos'),

  activateScenario: async (id: string) => {
    const res = await fetch(`/api/chaos/${id}`, { method: 'POST' })
    if (!res.ok) throw new Error(`chaos activate failed: ${res.status}`)
    return res.json() as Promise<{ active: string }>
  },

  startIncident: async (alertText: string) => {
    const res = await fetch('/api/incidents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ alertText }),
    })
    if (!res.ok) throw new Error(`start incident failed: ${res.status}`)
    return res.json() as Promise<{ incidentId: string; mode: string; scenarioId: string }>
  },

  report: (id: string) => get<TriageReport>(`/api/incidents/${id}/report`),
  ledger: (id: string) => get<LedgerEntry[]>(`/api/incidents/${id}/ledger`),
  events: (id: string) => get<InvestigationEvent[]>(`/api/incidents/${id}/events`),
}
