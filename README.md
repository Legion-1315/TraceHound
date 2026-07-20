# Triage Copilot

AI-powered incident triage for a distributed (simulated) banking estate. An agent investigates
production alerts the way a senior SRE would: scope the business flow, rank hypotheses, run the
cheapest decisive checks first, walk the dependency graph following evidence, and produce a triage
report where **every claim cites a recorded evidence-ledger entry**.

Everything — services, telemetry, change events — is simulated in-process. No Kafka, no Neo4j,
no real infrastructure: it runs on any laptop with Java and Node.

## Prerequisites

- Java 21+ (tested on Java 25)
- Node 18+ (tested on Node 26)
- No API key needed — the default **scripted mode** replays a correct investigation with realistic
  pacing and never touches the network. Optional: set `ANTHROPIC_API_KEY` + `TRIAGE_MODE=llm` for
  the live LLM agent.

## Start (two local processes)

```bash
# terminal 1 — backend on :8080
cd backend
./gradlew bootRun        # gradlew.bat bootRun on Windows

# terminal 2 — frontend on :5173
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

(`docker compose up --build` is provided as an alternative; the two-process path above is the one
verified end-to-end.)

## The 5-minute demo script

1. **Set the scene** (30s): point at the topology canvas — six services, two business flows.
   "MiFID Transaction Reporting" is the regulatory-critical path.
2. **Arm the fault** (15s): press **Ctrl+Shift+K** → chaos panel → pick **Schema Drift** → close.
   Telemetry across the estate rewrites instantly. (Let a judge pick the fault here if they want.)
3. **Start the investigation** (10s): click the **Schema Drift** preset alert button, then
   **Start investigation**. Note the alert fires on the *gateway* — three hops from the true cause.
4. **Narrate the canvas** (60s): nodes pulse blue as the agent probes, turn green when cleared,
   amber when suspect. Watch the hypothesis bars re-rank live as evidence arrives. The agent walks
   *upstream* from the symptom — gateway → builder → enrichment → ref-data — and flags
   **ref-data-service red: ROOT CAUSE** (a field rename in release 2.4.1).
5. **Show the report** (60s): Report tab — root cause with confidence %, an evidence chain where
   every link expands to the cited ledger entry, blast radius (the Trade Confirmations flow is also
   exposed — computed from the topology, not guessed), remediation drafts **awaiting human
   approval**, and a pre-filled mock ServiceNow ticket.
6. **The kicker — the learning loop** (45s): run the same Schema Drift alert **again**. The agent
   finds the past incident in memory, skips the broad sweep, and verifies the known cause directly:
   ~5 tool calls / ~6s instead of ~9 calls / ~11s. "It gets faster every incident it sees."
7. **Prove it's not a one-trick pony** (60s): chaos panel → **Certificate Expiry** → run its preset
   alert. The agent clears every upstream service and concludes *gateway-local* — explicitly
   resisting the reflex to blame the most recent deployment.
8. **Close on trust** (15s): footer — elapsed time, tool calls, **PII items redacted** before any
   telemetry reached the agent. Replay tab scrubs back through every step after the fact.

## The three fault scenarios

| Scenario | What happens | What the agent must get right |
|---|---|---|
| **schema-drift** (primary) | ref-data release renames `ccy` → `currencyCode`; enrichment silently nulls currency; gateway rejects submissions | Alert is 3 hops downstream of the cause; must walk upstream on evidence |
| **consumer-lag** | enrichment's queue consumer stalls after a config reload; volume collapses downstream | Distinguish "starved victim" from "broken service"; find where volume dies |
| **cert-expiry** | gateway's outbound TLS cert expires | Conclude gateway-local; do NOT blame the coincidental recent deployment |

## Architecture

```
frontend (React 18 + TS + Vite + Tailwind + React Flow, :5173)
   │  REST /api/*  +  STOMP WebSocket /ws → /topic/investigation/{id}
backend (Spring Boot 3.5, Java 21, :8080)
   ├─ topology.yaml            single source of truth for the estate
   ├─ SimulationEngine         healthy telemetry + scenario fault overlays
   ├─ AgentTools               6 read-only tools; every call → evidence ledger, PII-scrubbed
   ├─ ScriptedInvestigator     deterministic replay (default; demo safety net)
   ├─ LlmInvestigator          Anthropic tool-use loop (TRIAGE_MODE=llm + API key)
   ├─ EvidenceLedger           append-only; report citations resolve to entries
   ├─ IncidentMemoryService    JSON file store + TF-IDF cosine similarity (learning loop)
   └─ RedactionService         regex PII scrub + per-incident counter
```

Scripted and LLM modes emit the **same WebSocket events** (`NODE_STATUS`, `HYPOTHESIS_UPDATE`,
`TOOL_CALL`, `AGENT_THOUGHT`, `REPORT_READY`) so the UI is indistinguishable between them.
The live demo never depends on network access.

## Tests

```bash
cd backend && ./gradlew test
```

Acceptance tests activate each scenario, run the scripted investigator, and assert the diagnosed
root cause matches `expected_root_cause` and **every report citation resolves to a real ledger
entry**.

## Honest limitations (know these before the judges ask)

- **The estate is simulated.** Telemetry, change events and schema diffs are generated in-process
  from the scenario files. Integrating a real estate means implementing the six agent tools against
  real systems (logs → Splunk/ELK, changes → CMDB, topology → service catalog); the agent loop
  itself is unchanged.
- **Scripted mode is theatre — by design.** The default investigator replays a hand-written correct
  investigation (it does call the real tools against the simulated estate, so the ledger is
  genuine). The LLM mode is the real agent; it needs a key and its diagnosis quality/latency varies.
- **Scenario matching is exact-text.** Pasting an arbitrary alert that matches no preset falls back
  to the currently-armed (or default) scenario rather than genuinely parsing the alert.
- **Incident memory is naive.** TF-IDF cosine over short texts — fine for the demo corpus,
  similarity scores are low in absolute terms (threshold 0.25) and it would need embeddings at scale.
- **Blast radius is topological, not quantified.** It lists exposed flows/teams from the graph; it
  does not estimate trade counts or monetary impact.
- **Single-incident UI.** One investigation at a time per browser session; no auth, no persistence
  of incidents across backend restarts (only incident *memory* persists to `data/`).
