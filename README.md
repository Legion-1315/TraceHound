# Code-Catalyst

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

## Live mode: real services + real Claude agent

The `estate/` folder holds six real Spring Boot services (plus a mock regulator) that pass
trades to each other over HTTP and keep real logs and metrics. The triage app investigates them
with a live Claude agent; any free-text alert works, and the agent reports *out of scope* or
*no fault found* when that is the honest answer. There is no scripted fallback in this mode.

```bash
cp .env.example .env          # then put your ANTHROPIC_API_KEY in .env (git-ignored)
docker compose up --build     # first build takes a few minutes
```

Open **http://localhost:8080**, open the chaos panel (Ctrl+Shift+K), inject a real fault
(e.g. rename a field in ref-data, expire the gateway's TLS cert), wait for the alert or type
your own, and watch the agent investigate the running services.

**Your API key never goes into git.** It lives only in `.env` (ignored by `.gitignore`) or in
your host's environment variables. Spending guard-rails, all overridable in `.env`: at most 5
investigations per visitor per hour, 40 per day, US$5 of estimated spend per day, and 2 at once.
Also set a monthly spend limit in the Claude Console as a backstop.

`POST /api/chaos/admin/clear-history` (wipes logs, metrics and change history on every service,
used by evaluation runs) exists only when `TRIAGE_ADMIN_TOKEN` is set, and then needs that token
in an `X-Admin-Token` header. Leave it unset on a public demo.

**What has been verified so far:** the six faults, their alerts and recovery, run against the
real services without an LLM by `scripts/smoke-test.sh` (locally and in CI), and the compose
images build in CI. An end-to-end run with the Claude agent has **not** been done yet; results
will go in `docs/TASK_LOG.md`.

## Start (one process, one port)

The React app is compiled and folded into the Spring Boot jar, so a single command serves the UI,
the REST API and the WebSocket stream together on **:8080** — no reverse proxy, no CORS, no second
terminal.

```bash
cd backend
./gradlew bootRun        # gradlew.bat bootRun on Windows
```

Open **http://localhost:8080**.

The first run builds the frontend (~20s); after that Gradle skips the npm work until something
under `frontend/` changes. To produce a distributable artifact instead:

```bash
cd backend && ./gradlew bootJar && java -jar build/libs/backend-0.0.1-SNAPSHOT.jar
```

That one jar is the whole demo — copy it to any machine with a JRE and run it. `docker compose up
--build` produces the equivalent single container.

## Deploy the live demo to an Oracle Cloud Always Free VM

> **Not yet tried on a real VM.** The steps below have not been run end to end; the compose files
> they use build in CI. The "about 3 GB of RAM" figure comes from the compose `mem_limit`s
> (7 × 320 MB + 768 MB), not from a measurement.

Seven services plus the triage app need about 3 GB of RAM, more than PaaS free tiers give.
An Always Free Ampere (ARM) VM has up to 24 GB.

1. **Create the VM** (cloud.oracle.com → Compute → Instances → Create): image *Ubuntu 24.04*,
   shape *VM.Standard.A1.Flex* with 2 OCPU / 12 GB. Add your SSH public key. Note the public IP.
   If you get "out of host capacity", retry later or pick another availability domain.
2. **Open ports 80 and 443:** in the instance's subnet → Security List → add ingress rules for
   TCP 80 and 443 from `0.0.0.0/0`. Ubuntu's own firewall also blocks them; step 3 opens it.
3. **Install Docker and open the OS firewall** (on the VM, over SSH):
   ```bash
   sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
   sudo usermod -aG docker $USER && newgrp docker
   sudo iptables -I INPUT 6 -p tcp --dport 80 -j ACCEPT
   sudo iptables -I INPUT 6 -p tcp --dport 443 -j ACCEPT
   sudo netfilter-persistent save
   ```
4. **Get the code and your key onto the VM:**
   ```bash
   git clone https://github.com/Legion-1315/TraceHound.git && cd TraceHound
   cp .env.example .env && nano .env      # paste ANTHROPIC_API_KEY; the file stays on the VM
   chmod 600 .env
   ```
5. **Start it with HTTPS.** sslip.io turns your IP into a hostname, and Caddy gets a free
   certificate for it automatically. For IP `140.238.1.2`:
   ```bash
   DOMAIN=140-238-1-2.sslip.io docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
   ```
   Then open `https://140-238-1-2.sslip.io`. Containers restart on their own after a reboot.
6. **Update later:** `git pull` and run the same command again.

## Deploy to Google Cloud Run

The image is a single container serving UI + API + WebSocket, so Cloud Run needs no extra plumbing.
`server.port` reads Cloud Run's injected `$PORT`.

```bash
gcloud config set project YOUR_PROJECT_ID
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com

# Builds the Dockerfile on Cloud Build and deploys — no local Docker needed.
gcloud run deploy code-catalyst \
  --source . \
  --region europe-west2 \
  --allow-unauthenticated \
  --memory 1Gi \
  --cpu 1 \
  --min-instances 1 \
  --max-instances 1 \
  --no-cpu-throttling
```

Run it from the repo root (where the `Dockerfile` is). The three non-obvious flags matter:

| Flag | Why this app needs it |
|---|---|
| `--max-instances 1` | The STOMP broker is **in-process**. If `POST /api/incidents` lands on one instance and the WebSocket on another, the client sees an investigation that never streams. One instance sidesteps it; the alternative is session affinity, which is weaker. |
| `--no-cpu-throttling` | The investigation runs on a background executor after the POST returns. Cloud Run throttles CPU between requests by default, which would stall it mid-stream. |
| `--min-instances 1` | Keeps the instance warm. A cold start reseeds incident history and wipes incident memory — see the caveat below. |

### Building the image yourself (optional)

```bash
gcloud artifacts repositories create demo --repository-format=docker --location=europe-west2
gcloud auth configure-docker europe-west2-docker.pkg.dev

docker build -t europe-west2-docker.pkg.dev/YOUR_PROJECT_ID/demo/code-catalyst:v1 .
docker push europe-west2-docker.pkg.dev/YOUR_PROJECT_ID/demo/code-catalyst:v1

gcloud run deploy code-catalyst \
  --image europe-west2-docker.pkg.dev/YOUR_PROJECT_ID/demo/code-catalyst:v1 \
  --region europe-west2 --allow-unauthenticated \
  --memory 1Gi --min-instances 1 --max-instances 1 --no-cpu-throttling
```

### LLM mode — use Secret Manager, never `--set-env-vars`

An API key passed as a plain env var is visible in the service YAML, in deploy logs and to anyone
with Console read access. Store it as a secret:

```bash
printf '%s' "$ANTHROPIC_API_KEY" | gcloud secrets create anthropic-api-key --data-file=-

gcloud run services update code-catalyst --region europe-west2 \
  --set-env-vars TRIAGE_MODE=llm \
  --set-secrets ANTHROPIC_API_KEY=anthropic-api-key:latest
```

Grant the service account `roles/secretmanager.secretAccessor` if the deploy reports it can't read
the secret. **Scripted mode is the default and needs no key** — for a live demo, leave it that way.

### Caveat: state is ephemeral on Cloud Run

`data/incident-history.json` and `data/incident-memory.json` live on the container's in-memory
filesystem. They survive for the life of an instance but not a restart, so on a cold start the
analytics corpus reseeds to its 30 baseline incidents and learned incident memory is lost — which
also resets the learning-loop demo beat. `--min-instances 1` keeps this from biting mid-demo. For
durable history, point `TRIAGE_HISTORY_FILE` / `TRIAGE_MEMORY_FILE` at a GCS bucket mounted with
gcsfuse (Cloud Run volume mounts), or move the two stores to Firestore.

## Deploy to Render (free)

A `render.yaml` blueprint is included — Render builds straight from the existing `Dockerfile`,
no changes needed. `server.port` already reads Render's injected `$PORT` the same way it reads
Cloud Run's.

1. Push this repo to GitHub (already done if you're reading this from the repo).
2. In the Render dashboard: **New +** → **Blueprint** → connect this repo → Render detects
   `render.yaml` and provisions a free web service named `code-catalyst`.
3. First deploy takes ~3-5 min (Docker multi-stage build: Node build stage + Gradle build stage).
   Later deploys only rebuild what changed.
4. Open the assigned `https://code-catalyst-xxxx.onrender.com` URL once the deploy finishes.

`TRIAGE_MODE=scripted` (the default, no API key needed) is already set in `render.yaml` — no
manual environment setup required for a demo.

### Caveat: free tier sleeps + ephemeral state

Render's free tier spins the service down after ~15 minutes of no traffic; the next request
triggers a cold start (~30-60s). Like Cloud Run, `data/incident-history.json` and
`data/incident-memory.json` live on the container's filesystem, so a cold start or redeploy resets
the analytics corpus and wipes learned incident memory — same caveat as Cloud Run, just triggered
by inactivity instead of scale-to-zero. Load the app a minute or two before a live demo to warm it up.

### LLM mode on Render

Set `TRIAGE_MODE=llm` and `ANTHROPIC_API_KEY` as environment variables in the Render dashboard
(**Environment** tab on the service) — never commit them to `render.yaml` or any other file in
the repo.

### Optional: frontend dev server (hot reload)

Only needed when iterating on UI code and you want HMR. Run the backend as above, then:

```bash
cd frontend && npm run dev     # :5173, proxies /api and /ws to :8080
```

## The 5-minute demo script

1. **Set the scene** (30s): point at the topology canvas — six services, two business flows.
   "MiFID Transaction Reporting" is the regulatory-critical path.
2. **Start the investigation** (15s): click the **Schema Drift** preset alert button, then
   **Start investigation** — this fills the alert and arms the matching fault's telemetry in one
   motion. Note the alert fires on the *gateway* — three hops from the true cause.
3. **Narrate the canvas** (60s): nodes pulse blue as the agent probes, turn green when cleared,
   amber when suspect. Watch the hypothesis bars re-rank live as evidence arrives. The agent walks
   *upstream* from the symptom — gateway → builder → enrichment → ref-data — and flags
   **ref-data-service red: ROOT CAUSE** (a field rename in release 2.4.1).
4. **Show the report** (60s): Report tab — root cause with confidence %, an evidence chain where
   every link expands to the cited ledger entry, blast radius (the Trade Confirmations flow is also
   exposed — computed from the topology, not guessed), remediation drafts **awaiting human
   approval**, and a pre-filled mock JIRA issue.
5. **The kicker — the learning loop** (45s): run the same Schema Drift alert **again**. The agent
   finds the past incident in memory, skips the broad sweep, and verifies the known cause directly:
   ~5 tool calls / ~6s instead of ~9 calls / ~11s. "It gets faster every incident it sees."
6. **Prove it's not a one-trick pony** (45s): click the **Certificate Expiry** preset alert, then
   **Start investigation**. The agent clears every upstream service and concludes *gateway-local* —
   explicitly resisting the reflex to blame the most recent deployment.
7. **Close on trust** (15s): footer — elapsed time, tool calls, **PII items redacted** before any
   telemetry reached the agent. Replay tab scrubs back through every step after the fact.

## The six fault scenarios

| Scenario | Category | What happens | What the agent must get right |
|---|---|---|---|
| **schema-drift** (primary) | Data Contract | ref-data release renames `ccy` → `currencyCode`; enrichment silently nulls currency; gateway rejects submissions | Alert is 3 hops downstream of the cause; must walk upstream on evidence |
| **consumer-lag** | Messaging | enrichment's queue consumer stalls after a config reload; volume collapses downstream | Distinguish "starved victim" from "broken service"; find where volume dies |
| **cert-expiry** | Infrastructure | gateway's outbound TLS cert expires | Conclude gateway-local; do NOT blame the coincidental recent deployment |
| **latency-spike** | Performance | a ref-data DB index rebuild slows `/v1/currencies`; enrichment degrades estate-wide | Diagnose on latency alone — **zero errors** in the logs; rule out schema drift |
| **duplicate-trades** | Data Consistency | a trade-capture fast-path flag republishes trades; duplicates rejected at the gateway | Walk to the *first* hop; inflated throughput is the tell, not the error rate |
| **thread-pool-exhaustion** | Infrastructure | a submission-builder deploy under-sizes the sealing pool; it saturates under load | Latency *with* timeouts = resource-limited, not starved by an upstream |

## Architecture

One deployable unit — the UI ships inside the jar:

```
Spring Boot 3.5 / Java 21 — single process on :8080
   ├─ static/                  the compiled React app (bundled at build time)
   │     │  REST /api/*  +  STOMP WebSocket /ws → /topic/investigation/{id}
   ├─ SpaConfig                serves the UI; falls back to index.html on unknown paths
   ├─ topology.yaml            single source of truth for the estate
   ├─ SimulationEngine         healthy telemetry + scenario fault overlays
   ├─ AgentTools               6 read-only tools; every call → evidence ledger, PII-scrubbed
   ├─ ScriptedInvestigator     deterministic replay (default; demo safety net)
   ├─ LlmInvestigator          Anthropic tool-use loop (TRIAGE_MODE=llm + API key)
   ├─ EvidenceLedger           append-only; report citations resolve to entries
   ├─ IncidentMemoryService    JSON file store + TF-IDF cosine similarity (learning loop)
   ├─ IncidentHistoryService   seeded historical corpus + live append → Analytics tab
   └─ RedactionService         regex PII scrub + per-incident counter
```

Scripted and LLM modes emit the **same WebSocket events** (`NODE_STATUS`, `HYPOTHESIS_UPDATE`,
`TOOL_CALL`, `AGENT_THOUGHT`, `REPORT_READY`) so the UI is indistinguishable between them.
The live demo never depends on network access.

## Tests

```bash
cd backend && ./gradlew test                  # add -PskipFrontend to skip the npm build
```

Acceptance tests activate each scenario, run the scripted investigator, and assert the diagnosed
root cause matches `expected_root_cause` and **every report citation resolves to a real ledger
entry**. Further tests cover the seeded analytics corpus and its aggregation.

The estate services have their own tests (`cd estate && ./gradlew test`). The live smoke test
starts all seven services and the triage app from the built jars (no API key), injects each of
the six faults, checks the alert and log signature, and waits for recovery (about 11 minutes,
because alerts use a rolling one-minute window):

```bash
(cd estate && ./gradlew bootJar) && (cd backend && ./gradlew bootJar)
bash scripts/smoke-test.sh        # logs in .run/logs/<service>.log
```

## Honest limitations (know these before the judges ask)

- **Live mode: the Claude agent's accuracy is not measured yet.** The fault catalogue has
  `expected_root_cause_service` and keywords per fault for grading, but no graded run has been
  done. Until then, there is no accuracy, latency or cost figure for live mode.
- **Live mode: anyone who can open the UI can inject or clear faults.** `POST /api/chaos/{id}` is
  public by design (the chaos panel). On a shared deployment one visitor can change the estate
  under another visitor's investigation.
- **Live mode: alerts lag the fault.** The monitor polls every 10 s and the rules use a rolling
  one-minute window, so symptoms show 10-50 s after a fault is injected and clear about a minute
  after it is removed (measured by the smoke test).
- **Cost figures are estimates.** They use list prices from `application.properties`, and cache
  reads are counted at 0.1x input where Claude Sonnet 5.5 bills 0.05x, so they err high.

- **Simulated mode is simulated; live mode is a small demo estate.** In live mode the services are real
  processes and faults are real (renamed fields, an expired certificate, a stalled consumer), but they are
  injected on purpose into six demo services, not found in a production bank estate.
- **(Simulated mode)** The estate is simulated. Telemetry, change events and schema diffs are generated in-process
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
