import { create } from 'zustand'
import type {
  FeedItem, Hypothesis, InvestigationEvent, LedgerEntry, NodeStatus, ScenarioInfo, Topology, TriageReport,
} from './types'

interface TriageState {
  topology: Topology | null
  scenarios: ScenarioInfo[]
  activeScenario: string
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
  chaosOpen: boolean
  ticketOpen: boolean

  setTopology: (t: Topology) => void
  setScenarios: (s: ScenarioInfo[], active: string) => void
  setActiveScenario: (id: string) => void
  beginIncident: (incidentId: string, mode: string) => void
  applyEvent: (e: InvestigationEvent) => void
  completeIncident: (report: TriageReport, ledger: LedgerEntry[], events: InvestigationEvent[]) => void
  setReplayIndex: (i: number | null) => void
  setChaosOpen: (open: boolean) => void
  setTicketOpen: (open: boolean) => void
  resetInvestigation: () => void
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
}

export const useTriageStore = create<TriageState>((set) => ({
  topology: null,
  scenarios: [],
  activeScenario: '',
  chaosOpen: false,
  ticketOpen: false,
  ...emptyInvestigation,

  setTopology: (topology) => set({ topology }),
  setScenarios: (scenarios, activeScenario) => set({ scenarios, activeScenario }),
  setActiveScenario: (activeScenario) => set({ activeScenario }),

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
      }
      return next
    }),

  completeIncident: (report, ledger, replayEvents) =>
    set({ report, ledger, replayEvents, running: false }),

  setReplayIndex: (replayIndex) =>
    set((s) => {
      if (replayIndex === null) {
        // restore final state from the full event list
        return { replayIndex, ...reconstruct(s.replayEvents, s.replayEvents.length) }
      }
      return { replayIndex, ...reconstruct(s.replayEvents, replayIndex + 1) }
    }),

  setChaosOpen: (chaosOpen) => set({ chaosOpen }),
  setTicketOpen: (ticketOpen) => set({ ticketOpen }),
  resetInvestigation: () => set({ ...emptyInvestigation }),
}))

/** Rebuild canvas/hypotheses state from the first n replay events (Replay tab scrubbing). */
function reconstruct(events: InvestigationEvent[], n: number) {
  const nodeStatus: Record<string, NodeStatus> = {}
  let hypotheses: Hypothesis[] = []
  for (const e of events.slice(0, n)) {
    if (e.type === 'NODE_STATUS' && e.service && e.status) nodeStatus[e.service] = e.status
    if (e.type === 'HYPOTHESIS_UPDATE' && e.hypotheses) hypotheses = e.hypotheses
  }
  return { nodeStatus, hypotheses }
}
