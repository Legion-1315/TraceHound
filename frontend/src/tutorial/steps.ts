import type { RightTab } from '../store'

export type TourAction =
  | { kind: 'arm-fault'; scenarioId: string }
  | { kind: 'start-investigation'; alertText: string }
  | { kind: 'switch-tab'; tab: RightTab }
  | { kind: 'wait-for-report' }
  | { kind: 'open-chaos' }
  | { kind: 'close-chaos' }

export type Step = {
  /** data-tour attribute of the highlighted element, or "none" for a centered card with no spotlight. */
  target: string
  title: string
  body: string
  /** Optional action performed BEFORE the step renders (e.g. switch tab, arm fault, kick off the investigation). */
  onEnter?: TourAction
  /** Preferred side of the target to render the tooltip. auto = pick based on space. */
  side?: 'top' | 'bottom' | 'left' | 'right' | 'auto'
  /** If set, the step blocks Next until the condition holds. */
  waitFor?: 'report'
}

const SCHEMA_DRIFT_ALERT =
  'ALERT [SEV2] regulatory-gateway: MiFID submission validation failure rate 34% over 10m window ' +
  '(threshold 5%). Rule RG-VAL-001. Validator: ESMA-RTS22.'

export const TUTORIAL_STEPS: Step[] = [
  {
    target: 'none',
    title: 'Welcome to Triage Copilot',
    body:
      "This 2-minute tour walks you through a full production incident: how the agent scopes it, ranks hypotheses, " +
      "walks the estate on evidence, and produces a report where every claim cites a ledger entry. " +
      "We'll drive a real scripted investigation for you — you just watch.",
  },
  {
    target: 'canvas',
    title: 'The estate topology',
    body:
      "Six services across two business flows. MiFID Transaction Reporting is the regulated path " +
      "(trade-capture → enrichment → submission-builder → regulatory-gateway). ref-data-service feeds both. " +
      "Node colors will change live as the agent investigates: blue = probing, green = cleared, amber = suspect, red = ROOT CAUSE.",
    side: 'right',
  },
  {
    target: 'alert-box',
    title: 'The alert',
    body:
      "In production, an operator pastes an alert here. Three presets match our simulated fault scenarios. " +
      "We'll use the primary one: a MiFID submission validation failure firing on the regulatory-gateway.",
    side: 'right',
  },
  {
    target: 'start-btn',
    title: 'Kicking it off',
    body:
      "About to start the investigation for you. Watch three things happen at once: the reasoning feed on the left starts streaming, " +
      "nodes on the canvas light up in the order the agent probes them, and hypotheses on the right re-rank as evidence comes in.",
    onEnter: { kind: 'start-investigation', alertText: SCHEMA_DRIFT_ALERT },
    side: 'right',
  },
  {
    target: 'reasoning-feed',
    title: 'Agent reasoning',
    body:
      "Each › line is a thought; each ⚙ block is a real tool call recorded in the evidence ledger with a #id. " +
      "Notice the agent isn't just reading logs — it starts with change events and incident memory, because recent changes near symptom onset are the prime suspects.",
    side: 'right',
  },
  {
    target: 'tab-hypotheses',
    title: 'Live-ranked hypotheses',
    body:
      "Bars re-order in real time. Watch 'ref-data 2.4.1 schema change' climb from ~45% to ~92% as each check either confirms or rules out its rivals. " +
      "This is the visible probabilistic reasoning that makes the agent's judgement auditable.",
    side: 'left',
  },
  {
    target: 'canvas',
    title: 'Walking upstream',
    body:
      "The alert fired on the gateway (rightmost). But the agent walks LEFT: gateway rejects → builder passes null through → enrichment silently nulls currency → ref-data changed its schema. " +
      "The true cause is three hops upstream of the symptom.",
    side: 'right',
    waitFor: 'report',
  },
  {
    target: 'tab-report',
    title: 'The triage report',
    body:
      "Investigation complete. Switching to the Report tab now — root cause with confidence %, the evidence chain, blast radius, and remediation drafts.",
    onEnter: { kind: 'switch-tab', tab: 'Report' },
    side: 'left',
  },
  {
    target: 'root-cause',
    title: 'Root cause + owner',
    body:
      "ref-data-service Release 2.4.1 renamed 'ccy' → 'currencyCode'. 92% confidence. Note the owner (Reference Data Platform) is NOT the team that got paged (gateway ops) — that mis-routing is what triage tools are meant to fix.",
    side: 'left',
  },
  {
    target: 'causal-chain',
    title: 'Every claim cites evidence',
    body:
      "Each numbered link in the chain shows a #ledger-id. Click any of them to expand the exact tool call, params, result, and the agent's inference. " +
      "This is what turns 'the AI said so' into a defensible auditable trail.",
    side: 'left',
  },
  {
    target: 'blast-radius',
    title: 'Blast radius',
    body:
      "Computed from the topology, not guessed: which other business flows consume the faulty service? The Trade Confirmations flow also reads ref-data, so it's exposed too. " +
      "Owner teams surface automatically for downstream comms.",
    side: 'left',
  },
  {
    target: 'remediation',
    title: 'Remediation — awaiting approval',
    body:
      "Drafts only. The agent never applies changes; it prepares options and hands off. This is deliberate: at bank scale, execution stays with humans while the diagnosis is automated.",
    side: 'left',
  },
  {
    target: 'itsm-btn',
    title: 'Ticket handoff',
    body:
      "One click hands the entire investigation to ServiceNow (mocked here) with assignment group, evidence, and remediation pre-filled. " +
      "Skip for now — we'll continue.",
    side: 'left',
  },
  {
    target: 'tab-replay',
    title: 'Replay after the fact',
    body:
      "Every step is recorded. The Replay tab lets you scrub through the investigation to see what the agent knew at each moment. " +
      "Great for post-mortems and for teaching newer engineers.",
    onEnter: { kind: 'switch-tab', tab: 'Replay' },
    side: 'left',
  },
  {
    target: 'footer',
    title: 'Trust signals',
    body:
      "Elapsed time and tool-call count show you the cost. The PII-redacted counter is the key one for compliance: raw telemetry is scrubbed for account and client IDs before the agent sees any of it.",
    side: 'top',
  },
  {
    target: 'none',
    title: "One more thing — the chaos panel",
    body:
      "Press Ctrl+Shift+K anytime to open the chaos panel and arm a different fault (a stalled consumer, an expired TLS cert). " +
      "Try Certificate Expiry to see the agent NOT blame the coincidental recent deployment — resisting a real-world false-positive pattern. That's the tour. Enjoy.",
  },
]
