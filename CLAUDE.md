# Triage Copilot — AI-Powered Incident Triage for Distributed Systems

## What this project is

A hackathon prototype (Deutsche Bank) that diagnoses production incidents across a distributed application estate. It maintains a topology map of services and business flows, and an LLM agent uses that map to investigate incidents the way a senior SRE would: form ranked hypotheses, run the cheapest decisive checks first, walk the graph upstream/downstream following evidence, and produce a triage report where **every claim cites recorded evidence**.

This is a **small-scale, visually-driven demo**. Everything (services, telemetry, change events) is simulated in-process. No real Kafka, Neo4j, or OpenTelemetry — we simulate their behavior so the demo runs anywhere with `docker compose up` or two local processes. Prioritize demo reliability and visual clarity over infrastructure realism.

## Repository layout (monorepo)

```
triage-copilot/
├── CLAUDE.md                  (this file)
├── README.md                  (setup + demo script)
├── docker-compose.yml         (backend + frontend, optional)
├── backend/                   Spring Boot 3.x, Java 21, Gradle
│   └── src/main/resources/
│       ├── topology.yaml      (the estate definition — single source of truth)
│       └── scenarios/         (fault scenario definitions)
└── frontend/                  React 18 + TypeScript + Vite + Tailwind + React Flow
```

## The simulated estate (defined in topology.yaml)

Six nodes forming the "MiFID Transaction Reporting" business flow, plus a second flow for blast-radius demos:

1. **trade-capture** → publishes trades
2. **ref-data-service** → provides reference data (currencies, LEIs) — consumed by enrichment AND by the second flow
3. **enrichment-service** → enriches trades with ref data
4. **submission-builder** → builds regulatory reports
5. **regulatory-gateway** → submits to (fake) regulator, validates schema
6. **trade-confirmations** → second business flow, also consumes ref-data-service (exists to demonstrate blast radius)

topology.yaml schema per service:
```yaml
services:
  - id: enrichment-service
    name: Enrichment Service
    description: "Enriches captured trades with reference data (currency, LEI, venue) before report building"
    team: "Trade Enrichment Team"
    slack: "#te-support"
    upstream: [trade-capture, ref-data-service]
    downstream: [submission-builder]
    business_flows: [mifid-reporting]
    known_failure_modes:
      - "Ref-data schema drift causes silent null enrichment"
      - "Cache staleness after ref-data redeploy"
flows:
  - id: mifid-reporting
    name: "MiFID Transaction Reporting"
    path: [trade-capture, enrichment-service, submission-builder, regulatory-gateway]
    criticality: high
```

## Simulated telemetry & change events

Backend `SimulationEngine`:
- Generates a rolling window (last ~60 min, simulated clock) of log lines, basic metrics (throughput, error rate, latency), and change events per service. Healthy by default.
- When a **fault scenario** is activated, it rewrites the affected services' telemetry to show the fault's signature from the scenario's start time.

### Fault scenarios (in `scenarios/`, JSON or YAML)

**Scenario 1 — schema-drift (primary demo):** At T-13min, ref-data-service has a deployment change event ("Release 2.4.1 — field `ccy` renamed to `currencyCode`"). enrichment-service logs show schema-mapping WARNs from T-12min and enriches with null currency. submission-builder passes nulls through. regulatory-gateway shows validation ERRORs ("currency field missing") from T-10min and its error-rate metric spikes. Alert fires on the GATEWAY — three hops from the true cause. Correct diagnosis: ref-data schema change.

**Scenario 2 — consumer-lag:** enrichment-service throughput drops to near zero (simulated queue consumer stall after a config change event on enrichment-service); downstream services show falling volume, gateway shows "no submissions received" alerts. Correct diagnosis: enrichment consumer stalled after config change.

**Scenario 3 — cert-expiry:** regulatory-gateway logs TLS handshake failures to the regulator endpoint; no upstream involvement. Correct diagnosis: gateway-local. (Proves the agent doesn't always blame upstream.)

Each scenario file declares: affected services, injected log lines/metric shapes per service with relative timestamps, the change events to plant, the alert text the user pastes, and `expected_root_cause` (used by the verification test).

## Backend design (Spring Boot)

### REST + WebSocket API
- `POST /api/incidents` {alertText} → starts an investigation, returns incidentId
- `GET /api/topology` → graph JSON for the canvas
- `POST /api/chaos/{scenarioId}` → activate a fault scenario (powers the hidden chaos panel)
- `GET /api/incidents/{id}/report` → final triage report JSON
- `GET /api/incidents/{id}/ledger` → full evidence ledger
- WebSocket topic `/topic/investigation/{id}` → streams investigation events to the UI in real time:
  `{type: NODE_STATUS, service, status}` (PROBING|CLEARED|SUSPECT|ROOT_CAUSE)
  `{type: HYPOTHESIS_UPDATE, hypotheses:[{text, probability}]}`
  `{type: TOOL_CALL, tool, params, summary}`
  `{type: AGENT_THOUGHT, text}` (one-line reasoning updates)
  `{type: REPORT_READY}`

### Agent tools (read-only, each call recorded in the evidence ledger)
- `get_topology(flowId?)` — subgraph for a business flow
- `query_logs(serviceId, minutes)` — recent scrubbed log lines
- `query_metrics(serviceId)` — error rate / throughput / latency summary
- `get_change_events(subgraph, minutes)` — recent deployments/config changes
- `compare_schema(serviceId)` — pre/post-deploy schema diff (scenario 1)
- `search_incident_memory(symptomsText)` — top-k similar past incidents

### Orchestrator loop (the core)
Implemented with the Anthropic API (tool use / function calling) via simple HTTP calls or the official Java SDK. System prompt encodes the protocol:
1. SCOPE: map alert → business flow → pull subgraph (emit NODE_STATUS grey for each).
2. HYPOTHESIZE: call `get_change_events` + `search_incident_memory`; produce 3–4 ranked hypotheses with probabilities (emit HYPOTHESIS_UPDATE). Recent change events near symptom onset must be weighted heavily.
3. INVESTIGATE: loop — pick cheapest decisive check, call a tool, emit TOOL_CALL + NODE_STATUS + updated HYPOTHESIS_UPDATE, walk upstream/downstream per evidence.
4. VERIFY: before concluding, produce a causal chain where each link cites a ledger entry ID. If any link lacks evidence, keep investigating (max ~12 tool calls, then report inconclusive with ruled-out list).
5. REPORT: root cause, confidence %, evidence chain with citations, blast radius (computed from topology: which other flows consume the faulty service), owning team from topology.yaml, draft remediation marked "awaiting human approval".
6. LEARN: write {symptoms, path, root cause, resolution} to incident memory (JSON file store; keyword/cosine-over-TF-IDF similarity is fine — no external embedding service required).

### Deterministic fallback mode (critical for demo safety)
If `ANTHROPIC_API_KEY` is absent OR `--demo-mode=scripted`, run a scripted investigator that replays the correct investigation for the active scenario with realistic pacing (800–1500ms between steps), emitting the same WebSocket events. The UI must be indistinguishable between modes. **The live demo must never depend on network access.**

### Evidence ledger
Append-only table (H2 in-memory is fine) — id, timestamp, tool, params, resultSummary, agentInference. Report citations reference ledger IDs. Expose via API for the Replay view.

### Redaction gateway
A `RedactionService` that scrubs simulated PII (account numbers, client IDs — plant a few in the logs deliberately) via regex before logs reach the agent, replacing with `[REDACTED:ACCOUNT]`. Show a counter in the UI footer: "PII items redacted this investigation: N". Small feature, big talking point.

## Frontend design (React 18 + TS + Vite + Tailwind + React Flow)

Three-panel layout, dark theme, professional (bank-demo aesthetic, not toy-like):

1. **Left — Incident chat:** textarea to paste the alert, Start button, then a streaming feed of AGENT_THOUGHT lines (like a terminal of the agent thinking). Preset alert buttons for the 3 scenarios to avoid typing during the demo.
2. **Center — Live investigation canvas (the star):** React Flow graph of the subgraph. Node states: grey (unknown), pulsing blue (probing), green (cleared), amber (suspect), red (root cause). Animate edges on the path being walked. Auto-layout left→right in flow order. Business-flow name as a title chip. Smooth transitions — this panel is what wins the demo.
3. **Right — Tabs:**
   - *Hypotheses:* live-ranked list with probability bars that visibly re-rank as evidence arrives.
   - *Report:* rendered triage report; each evidence link expandable to show the cited ledger entry; blast-radius section listing affected flows/teams; "Create ITSM ticket" button that shows a pre-filled mock ServiceNow ticket modal.
   - *Replay:* timeline scrubber over the evidence ledger — step through the investigation after the fact.
4. **Hidden chaos panel:** keyboard shortcut (e.g., Ctrl+Shift+K) opens fault-injection controls listing the 3 scenarios. Lets a judge pick the fault live.
5. **Footer:** elapsed investigation time, tool-call count, PII-redacted count — measured numbers for the pitch.

## Build order (do it in this sequence; keep it runnable at every step)

1. Backend skeleton: topology.yaml loader, `/api/topology`, SimulationEngine with scenario 1, WebSocket wiring.
2. Scripted investigator for scenario 1 emitting the full event stream (deterministic mode first — it's the safety net AND the integration test for the UI).
3. Frontend: canvas + chat + hypothesis panel consuming the stream. At this point the demo already works end-to-end.
4. Triage report + evidence ledger + report tab.
5. Real LLM agent mode with tool use (behind the API-key flag), reusing the same tools/events.
6. Scenarios 2 & 3 + chaos panel.
7. Incident memory + learning-loop demo (run scenario 1 twice; second run retrieves the past incident and short-circuits — show diagnosis time drop).
8. Replay tab, redaction counter, ITSM modal, polish.

## Testing / acceptance
- Unit test: for each scenario, the scripted investigator's final root cause equals `expected_root_cause`.
- Integration test: POST incident → report endpoint returns citations that all resolve to real ledger entries.
- For LLM mode: a smoke test asserting scenario 1 diagnosis contains "ref-data" and "schema" (tolerate wording variance).
- `README.md` must contain: prerequisites, one-command startup, the 5-minute demo script (which buttons to press in which order), and troubleshooting.

## Style & constraints
- Java 21, Spring Boot 3.x, Gradle. No Lombok surprises — keep it readable for a team walkthrough.
- Frontend: functional components + hooks, no Redux (Zustand or context is fine).
- No external infra dependencies (no Neo4j/Kafka/Postgres) — in-memory + files only, so it runs on any laptop.
- Every simulated log line should look like a real bank-grade log line (timestamps, correlation IDs, log levels).
- Commit after each build-order step with a clear message.
