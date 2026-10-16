import { create } from 'zustand'
import type {
  AgentUsage, FeedItem, Hypothesis, InvestigationEvent, LedgerEntry, LiveAlert, ModeInfo, NodeStatus, ScenarioInfo,
  Topology, TriageReport,
} from './types'
import { api } from './api'
import { subscribeToInvestigation } from './ws'

export type RightTab = 'Hypotheses' | 'Report' | 'Replay' | 'Analytics'

interface TriageState {
  topology: Topology | null
  scenarios: ScenarioInfo[]
  modeInfo: ModeInfo | null
  alerts: LiveAlert[]
  activeFaults: string[]
  chaosOpen: boolean
  usage: AgentUsage | null
  error: string | null
  incidentId: string | null
  mode: string
  running: boolean
  startedAt: number | null
  nodeStatus: Record<string, NodeStatus>
  feed: FeedItem[]
  hypotheses: Hypothesis[]
  toolCallCount: number
  report: TriageReport | null
  ledger: LedgerEntry[]
  replayEvents: InvestigationEvent[]
  replayIndex: number | null
  ticketOpen: boolean
  activeTab: RightTab

  // tutorial
  tourActive: boolean
  tourStep: number

  setTopology: (t: Topology) => void
  setScenarios: (s: ScenarioInfo[], active: string) => void
  setModeInfo: (m: ModeInfo) => void
  setAlerts: (a: LiveAlert[]) => void
  setActiveFaults: (f: string[]) => void
  setChaosOpen: (open: boolean) => void
  clearError: () => void
  beginIncident: (incidentId: string, mode: string) => void
  applyEvent: (e: InvestigationEvent) => void
  completeIncident: (report: TriageReport, ledger: LedgerEntry[], events: InvestigationEvent[]) => void
  setReplayIndex: (i: number | null) => void
  setTicketOpen: (open: boolean) => void
  setActiveTab: (t: RightTab) => void
  resetInvestigation: () => void

  startInvestigation: (alertText: string) => Promise<void>

  startTour: () => void
  nextStep: () => void
  prevStep: () => void
  endTour: () => void
}

const emptyInvestigation = {
  incidentId: null as string | null,
  mode: '',
  running: false,
  startedAt: null as number | null,
  nodeStatus: {} as Record<string, NodeStatus>,
  feed: [] as FeedItem[],
  hypotheses: [] as Hypothesis[],
  toolCallCount: 0,
  report: null as TriageReport | null,
  ledger: [] as LedgerEntry[],
  replayEvents: [] as InvestigationEvent[],
  replayIndex: null as number | null,
  usage: null as AgentUsage | null,
  error: null as string | null,
}

// Held outside store state — a live WebSocket subscription must survive re-renders and cannot be serialised.
let currentUnsubscribe: (() => void) | null = null

export const useTriageStore = create<TriageState>((set, get) => ({
  topology: null,
  scenarios: [],
  modeInfo: null,
  alerts: [],
  activeFaults: [],
  chaosOpen: false,
  ticketOpen: false,
  activeTab: 'Hypotheses',
  tourActive: false,
  tourStep: 0,
  ...emptyInvestigation,

  setTopology: (topology) => set({ topology }),
  setScenarios: (scenarios) => set({ scenarios }),
  setModeInfo: (modeInfo) => set({ modeInfo }),
  setAlerts: (alerts) => set({ alerts }),
  setActiveFaults: (activeFaults) => set({ activeFaults }),
  setChaosOpen: (chaosOpen) => set({ chaosOpen }),
  clearError: () => set({ error: null }),

  beginIncident: (incidentId, mode) =>
    set({ ...emptyInvestigation, incidentId, mode, running: true, startedAt: Date.now() }),

  applyEvent: (e) =>
    set((s) => {
      const next: Partial<TriageState> = {}
      if (e.type === 'NODE_STATUS' && e.service && e.status) {
        next.nodeStatus = { ...s.nodeStatus, [e.service]: e.status }
      } else if (e.type === 'AGENT_THOUGHT' && e.text) {
        next.feed = [...s.feed, { kind: 'thought', text: e.text, at: e.at }]
      } else if (e.type === 'TOOL_CALL') {
        next.feed = [...s.feed, {
          kind: 'tool', text: e.summary ?? '', tool: e.tool, ledgerId: e.ledgerId, at: e.at,
        }]
        next.toolCallCount = s.toolCallCount + 1
      } else if (e.type === 'HYPOTHESIS_UPDATE' && e.hypotheses) {
        next.hypotheses = e.hypotheses
      } else if (e.type === 'USAGE' && e.usage) {
        next.usage = e.usage
      } else if (e.type === 'ERROR') {
        next.error = e.text ?? 'Investigation failed'
        next.running = false
      }
      return next
    }),

  completeIncident: (report, ledger, replayEvents) =>
    set({ report, ledger, replayEvents, running: false }),

  setReplayIndex: (replayIndex) =>
    set((s) => {
      if (replayIndex === null) {
        return { replayIndex, ...reconstruct(s.replayEvents, s.replayEvents.length) }
      }
      return { replayIndex, ...reconstruct(s.replayEvents, replayIndex + 1) }
    }),

  setTicketOpen: (ticketOpen) => set({ ticketOpen }),
  setActiveTab: (activeTab) => set({ activeTab }),
  resetInvestigation: () => set({ ...emptyInvestigation, activeTab: 'Hypotheses' }),

  startInvestigation: async (alertText) => {
    if (get().running) return
    set({ error: null })
    let started: { incidentId: string; mode: string }
    try {
      started = await api.startIncident(alertText)
    } catch (err) {
      // rate limit, spend cap, missing API key… — shown in the panel, never silently swapped for a replay
      set({ error: err instanceof Error ? err.message : String(err) })
      return
    }
    const { incidentId, mode } = started
    get().beginIncident(incidentId, mode)
    currentUnsubscribe?.()
    currentUnsubscribe = subscribeToInvestigation(incidentId, (e) => {
      get().applyEvent(e)
      if (e.type === 'REPORT_READY') void pollReport(incidentId)
      if (e.type === 'ERROR') {
        currentUnsubscribe?.()
        currentUnsubscribe = null
      }
    })
    void watchIncident(incidentId)
  },

  startTour: () => {
    currentUnsubscribe?.()
    currentUnsubscribe = null
    set({ ...emptyInvestigation, activeTab: 'Hypotheses', tourActive: true, tourStep: 0 })
  },
  nextStep: () => set((s) => ({ tourStep: s.tourStep + 1 })),
  prevStep: () => set((s) => ({ tourStep: Math.max(0, s.tourStep - 1) })),
  endTour: () => set({ tourActive: false, tourStep: 0 }),
}))

async function pollReport(incidentId: string) {
  const store = useTriageStore.getState()
  for (let attempt = 0; attempt < 40; attempt++) {
    try {
      const report = await api.report(incidentId)
      if (report && 'rootCause' in report) {
        const [ledger, events] = await Promise.all([api.ledger(incidentId), api.events(incidentId)])
        store.completeIncident(report, ledger, events)
        currentUnsubscribe?.()
        currentUnsubscribe = null
        return
      }
    } catch { /* not ready yet */ }
    await new Promise((r) => setTimeout(r, 400))
  }
}

/**
 * Safety net for events the WebSocket can miss (e.g. a failure before the subscription connected):
 * poll the incident status while it is the current one and still running.
 */
async function watchIncident(incidentId: string) {
  for (;;) {
    await new Promise((r) => setTimeout(r, 2000))
    const s = useTriageStore.getState()
    if (s.incidentId !== incidentId || !s.running) return
    try {
      const status = await api.incident(incidentId)
      if (status.status === 'FAILED') {
        useTriageStore.setState({ running: false, error: status.error ?? 'Investigation failed' })
        return
      }
      if (status.status === 'COMPLETE' && !useTriageStore.getState().report) {
        void pollReport(incidentId)
        return
      }
    } catch { /* transient — try again */ }
  }
}

function reconstruct(events: InvestigationEvent[], n: number) {
  const nodeStatus: Record<string, NodeStatus> = {}
  let hypotheses: Hypothesis[] = []
  for (const e of events.slice(0, n)) {
    if (e.type === 'NODE_STATUS' && e.service && e.status) nodeStatus[e.service] = e.status
    if (e.type === 'HYPOTHESIS_UPDATE' && e.hypotheses) hypotheses = e.hypotheses
  }
  return { nodeStatus, hypotheses }
}
