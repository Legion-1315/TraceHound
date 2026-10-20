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

export interface AgentUsage {
  model: string
  apiCalls: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  estimatedCostUsd: number
}

export interface InvestigationEvent {
  type: 'NODE_STATUS' | 'AGENT_THOUGHT' | 'TOOL_CALL' | 'HYPOTHESIS_UPDATE' | 'REPORT_READY' | 'USAGE' | 'ERROR'
  at: string
  service?: string
  status?: NodeStatus
  text?: string
  tool?: string
  params?: Record<string, unknown>
  summary?: string
  ledgerId?: number
  hypotheses?: Hypothesis[]
  usage?: AgentUsage
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
  outcome: 'ROOT_CAUSE_FOUND' | 'INCONCLUSIVE' | 'NO_FAULT_FOUND' | 'OUT_OF_SCOPE'
  rootCauseService: string | null
  mode: 'llm' | 'scripted'
  usage: AgentUsage | null
}

export interface ModeInfo {
  estate: 'simulated' | 'live'
  investigator: 'llm' | 'scripted'
  llmConfigured: boolean
  /** "anthropic" (Claude) or "ollama" (a local model) */
  provider?: string
  model: string | null
  budget: {
    investigationsToday: number
    maxInvestigationsPerDay: number
    estimatedCostTodayUsd: number
    maxDailyCostUsd: number
    maxPerClientPerHour: number
  }
}

export interface LiveAlert {
  id: string
  serviceId: string
  ruleId: string
  severity: string
  text: string
  firstSeen: string
  lastSeen: string
}

export interface ScenarioInfo {
  id: string
  name: string
  description: string
  alertText: string
  category?: string
}

export interface ChaosState {
  estate: 'simulated' | 'live'
  scenarios: ScenarioInfo[]
  active: string
  activeFaults: string[]
}

export interface ChartEntry {
  name: string
  value: number
}

export interface DashboardData {
  summary: {
    totalIncidents: number
    avgMttr: string
    aiSuccess: string
    criticalIncidents: number
  }
  categories: ChartEntry[]
  services: ChartEntry[]
  trend: ChartEntry[]
  severity: ChartEntry[]
}

export interface FeedItem {
  kind: 'thought' | 'tool'
  text: string
  tool?: string
  ledgerId?: number
  at: string
}

/** "Claude agent (model)" or "Local agent via Ollama (model)", for the mode labels. */
export function agentLabel(info: { provider?: string; model: string | null } | null | undefined): string {
  const name = info?.provider === 'ollama' ? 'Local agent via Ollama' : 'Claude agent'
  return info?.model ? `${name} (${info.model})` : name
}
