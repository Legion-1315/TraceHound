export interface ServiceDef {
  id: string
  name: string
  description: string
  team: string
  slack: string
  upstream: string[]
  downstream: string[]
  business_flows: string[]
  known_failure_modes: string[]
}

export interface FlowDef {
  id: string
  name: string
  path: string[]
  criticality: string
}

export interface Topology {
  services: ServiceDef[]
  flows: FlowDef[]
}

export type NodeStatus = 'UNKNOWN' | 'PROBING' | 'CLEARED' | 'SUSPECT' | 'ROOT_CAUSE'

export interface Hypothesis {
  text: string
  probability: number
}

export interface InvestigationEvent {
  type: 'NODE_STATUS' | 'AGENT_THOUGHT' | 'TOOL_CALL' | 'HYPOTHESIS_UPDATE' | 'REPORT_READY'
  at: string
  service?: string
  status?: NodeStatus
  text?: string
  tool?: string
  params?: Record<string, unknown>
  summary?: string
  ledgerId?: number
  hypotheses?: Hypothesis[]
}

export interface LedgerEntry {
  id: number
  timestamp: string
  tool: string
  params: Record<string, unknown>
  resultSummary: string
  agentInference: string
}

export interface CausalLink {
  claim: string
  ledgerId: number
}

export interface BlastRadiusEntry {
  flowId: string
  flowName: string
  criticality: string
  impactedService: string
  owningTeam: string
}

export interface TriageReport {
  incidentId: string
  rootCause: string
  confidencePct: number
  summary: string
  causalChain: CausalLink[]
  blastRadius: BlastRadiusEntry[]
  owningTeam: string
  owningSlack: string
  remediation: string[]
  ruledOut: string[]
  elapsedMs: number
  toolCallCount: number
  piiRedactedCount: number
  inconclusive: boolean
}

export interface ScenarioInfo {
  id: string
  name: string
  description: string
  alertText: string
}

export interface FeedItem {
  kind: 'thought' | 'tool'
  text: string
  tool?: string
  ledgerId?: number
  at: string
}
