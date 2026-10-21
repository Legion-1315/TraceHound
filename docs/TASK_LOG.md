# Task log

Work log for `docs/CLAUDE_CODE_TASKS.md`, on branch `feature/live-estate`.
Local machine: Windows 11, Java 25, Node 26, Docker 29.6 (CI uses Java 21, Node 22).

## Task 0: branch

The working tree on `master` was clean. Fetched and checked out `feature/live-estate`
(up to date with origin at `bfa9a05`).

## Task 1: compile and run every test

At the start, everything compiled, but 8 of 13 estate tests and 19 of 28 backend tests failed.

| Project | Failure | Cause | Fix |
|---|---|---|---|
| estate | 7 `*BootTest`s: `No default constructor found` for `OpsMetrics` | Two constructors and none marked `@Autowired`, so Spring looked for a no-arg constructor | `@Autowired` on the public `@Value` constructor |
| backend | 17 Spring context tests: same error for `LlmBudget` | Same pattern | Same fix |
| backend | `LogSummarizerTest` (2 tests): `34ms` not masked to `<n>ms` | The number mask used `\b\d+\b`, and there is no word boundary between `4` and `m`, so any number followed by a unit stayed unmasked and identical log lines with different latencies became separate patterns | Mask digits not preceded by a letter, digit, `_` or `.` (`RTS22`, `F31` stay intact) |
| estate | `OpsMetricsTest.errorRateThroughputAndP95ComeFromTheLastMinute`: expected p95 900, got 10 | **The test was wrong.** It recorded 95 fast (10 ms) and 5 slow (900 ms) samples. With nearest-rank p95 the 95th of 100 sorted values is still a fast one, so 10 is correct | The test now uses 94 fast and 6 slow samples, so it still checks that a slow tail shows up in p95. The code is unchanged |

Result: estate 13/13, backend 28/28, `npm ci && npm run build` OK, `npx oxlint src` clean.
The Vite build warns that the bundle is 843.6 kB; see task 6.

## Task 2: live estate smoke test

`scripts/smoke-test.sh` starts the seven estate services and the triage app (`TRIAGE_ESTATE=live`,
no key) from the built jars, with logs in `.run/logs/<service>.log`. The triage app runs from an
empty directory with `ANTHROPIC_API_KEY=` blanked, so its optional `.env` import cannot pick up
a key, and the script checks that `/api/mode` reports `llmConfigured: false`. For each fault it
injects through `POST /api/chaos/{id}`, checks `/api/chaos` lists it as active, and polls (3 s
interval) until the symptom shows in the triage app's `/api/alerts` and in the affected
service's `/ops/logs`. Then it resets with `POST /api/chaos/none` and waits until there are no
alerts and all six services are still UP. It exits non-zero on the first failure and kills
every process it started on exit, including failures.

Measured locally (Windows 11, Java 25), run 5, the first full pass:

| Fault | Symptom checked | Time to symptom | Time to recover |
|---|---|---|---|
| schema-drift | `TH-ERR-001` on regulatory-gateway; enrichment WARN `'ccy' absent`; gateway `currency missing` | 10 s | 58 s |
| consumer-lag | `TH-VOL-001` (low throughput) on enrichment-service | 39 s | 71 s |
| cert-expiry | `TH-ERR-001` on regulatory-gateway; `TLS/transport error` in gateway logs | 10 s | 59 s |
| latency-spike | `TH-LAT-001` on ref-data-service **and** enrichment-service; `Slow query on CCY_REF`; no error alert on enrichment | 9 s / 10 s | 62 s |
| duplicate-trades | `TH-ERR-001` on regulatory-gateway; `duplicate transaction reference`; `TH-VOL-002` (high throughput) on trade-capture | 19 s / 48 s | 39 s |
| thread-pool-exhaustion | `TH-ERR-001` on submission-builder; `Sealing pool saturated` | 49 s | 72 s |

Startup to all-UP: 8 s. Whole run: about 11 minutes, because the alert rules use a rolling
1-minute window. Symptoms and recovery have to fill and drain that window.

Problems found on the way:
- Runs 1-4 failed and I fixed each: a relative jar path broke after the triage subshell
  `cd`'d away; Windows Python printed CRLF so `:UP$` never matched; and the local
  `backend/build/libs` jar was a stale `master` build from August with no live endpoints
  (`./gradlew test` does not rebuild it). Of the three, only the jar path would also have
  broken CI; CI uses Linux Python and builds fresh jars.
- **CI never failed on a test failure.** Without an explicit `shell:`, GitHub runs steps as
  `bash -e {0}`, with no `pipefail`, so `./gradlew test | tee log` passed when Gradle failed. The
  run for `bfa9a05` logged `13 tests completed, 8 failed` and `28 tests completed, 19 failed`
  and was green. The workflow now sets `defaults.run.shell: bash`, which runs steps with `-eo pipefail`.
- I removed the `if [ ! -f scripts/smoke-test.sh ]` shortcut, so a missing script now fails the job.

## Task 3: security fixes

1. **`POST /api/chaos/admin/clear-history` needs a token.** New setting `triage.admin-token`
   (env `TRIAGE_ADMIN_TOKEN`, empty in `.env.example`). With no token set the endpoint
   returns 404. With a token set, the request must send it in `X-Admin-Token`, compared in
   constant time; a missing or wrong token gets 401. Tests: `ClearHistoryAuthTest` (3 tests,
   with the live-chaos service mocked so the authorised path really calls `clearHistoryAll()`).
   Checked against the built jar: unset → 404/404/404; set → 401 (none), 401 (wrong), 200 (right).
   The smoke test also checks the 404 at the end of the run.
2. **Pricing and model id.** I checked both on 2026-10-17:
   - https://platform.claude.com/docs/en/about-claude/pricing lists Claude Sonnet 5.5 at
     $2 / MTok input and $10 / MTok output (5 m cache write $2.50, cache hit $0.10).
   - https://platform.claude.com/docs/en/about-claude/models/overview lists `claude-sonnet-5-5`
     as a current model (retirement not before 2027-09-28).
   So the defaults (2.0 / 10.0) were already right; only the comment calling them placeholders
   was wrong. I replaced it with the source links. One small inaccuracy remains: `LlmBudget.cost()`
   prices cache reads at 0.1× input, while Sonnet 5.5 bills 0.05×. The estimate errs high, which is
   the safe side for the spend cap, so I left the code unchanged.
3. **Compose exposure.** `docker compose config` (with and without the prod override) publishes
   only `triage` (`127.0.0.1:8080` by default) and, in prod, Caddy on 80/443. No estate service
   has `ports:`, so their `/chaos` and `/ops/admin/*` endpoints are reachable only inside the
   compose network. Caddy proxies only to `triage:8080`.

**Decision for the owner:** the triage app's own `POST /api/chaos/{id}` and `/api/chaos/none`
are still public by design (the chaos panel). On a public deployment, any visitor can inject or
clear a fault while someone else is investigating. Options: put it behind the same admin
token, or rate-limit it. I have not changed it.

### Task 2 follow-up: the first CI smoke run found a real race in the gateway

CI run 39011030491 (commit `0cfbb56`) failed on `duplicate-trades`: no error alert on the gateway
within 150 s, and every service showed about 238/min instead of 120. The duplicates were
being **reported to the regulator** instead of rejected. Cause: `RegulatoryGateway` checked
`reported.contains(id)` before the ARM call but added the id only after the ARM answered.
The two copies of a trade travel in parallel (4 enrichment consumers, 4 sealing threads) and
reach the gateway milliseconds apart, so both passed the check. On my machine the timing
happened to serialise them; on the CI runner it did not.

Fix: claim the reference with an atomic `reported.add(id)` before calling the ARM, and release
it if the submission fails, so a retry still works. New test
`RegulatoryGatewayDuplicateTest` sends the same trade twice at once against a slow fake ARM.
It failed on the old code (`[202, 202]`, both reported) and passes on the new one (`[202, 409]`,
one ARM report).

The same CI run also showed "recovered" 9 s after consumer-lag was cleared: a gap between one
alert clearing and the next firing while the backlog drained. "Recovered" now means no alerts
for 25 s in a row (at least two monitor polls).

Local rerun with both fixes (run 6): PASSED. Symptom times 13 / 32 / 13 / 13 / 13 / 42 s and
recovery 87 / 36 / 87 / 87 / 87 / 98 s, in catalogue order. Recovery includes the 25 s quiet
period. The clear-history check returned 404.

## Task 6: polish

- **Code-split.** `CanvasPanel` (React Flow) and `AnalyticsTab` (Recharts) are loaded with
  `React.lazy` + `Suspense`. Before: one 843.6 kB chunk (Vite warning). After: `index` 260.1 kB,
  `CanvasPanel` 174.9 kB (+15.9 kB CSS), `AnalyticsTab` 397.0 kB, `value` 11.6 kB; no warning.
  The first load fetches about 435 kB of JS instead of 844 kB. Recharts now loads only when the
  Analytics tab is first opened. Checked in a browser against the built jar (simulated mode): the
  canvas renders 6 nodes, `AnalyticsTab-*.js` is fetched only after the tab is clicked, the tab
  renders 3 charts, and there are no console errors.
- **CI warnings.** Every action now uses the lowest major that runs on Node 24, which removes the
  "Node.js 20 is deprecated" and "setup-java v4 is deprecated" annotations: checkout v5,
  setup-java v5, setup-node v5, setup-gradle v5, upload-artifact v6, download-artifact v7,
  github-script v8. (I read each version's `action.yml` to check `runs.using: node24`.) The only
  breaking change listed for these (setup-node v5 auto-caching) does not matter here because the
  workflow already sets `cache: npm`. Still open: GitHub's notice that `ubuntu-latest` moves to
  Ubuntu 26 from 2026-10-19. I left `ubuntu-latest` unchanged; pin `ubuntu-24.04` if CI should
  not change under you.
- **README.** The live-mode section now says what has and has not been verified, and documents
  `TRIAGE_ADMIN_TOKEN`. The Oracle section says it has not been run on a real VM yet (and that
  the 3 GB figure comes from `mem_limit`s). Tests documents the estate tests and the smoke test.
  "Honest limitations" adds: LLM accuracy not measured yet, the public chaos API, alert lag
  (measured), and cost estimates that err high.

## Local end-to-end check (2026-10-20), without an API key

The owner asked for a full local test before updating the README, and said to leave tasks 4 and 5
(LLM spend) for later. There is no `.env` on this machine, so no Claude call was possible or made.

- **Docker route: not tested locally.** Docker Desktop would not start on this machine (its
  process exits at once, and WSL has no distributions, so the `docker-desktop` distro is
  missing). Repairing that means reinstalling system components, which is the owner's call. The
  compose images do build in CI (`Docker images build` job, green).
- **New `scripts/run-local.sh`:** runs the whole live stack (7 estate processes and the triage
  app) with plain Java, no Docker, and stops everything on Ctrl+C. Tested: all six services UP,
  regulator on 8443, `/api/mode` = live, `llmConfigured: false`. Nothing was left running after stop.
- **Bug found and fixed: an inherited `PORT` broke the estate.** The first two starts failed
  because the launcher had `PORT=8080` set, and every estate profile reads
  `server.port=${PORT:<default>}`. ref-data-service took 8080 and the triage app could not bind.
  Any shell with `PORT` set (PaaS shells, some IDE launchers) hits the same problem. Both
  `run-local.sh` and `smoke-test.sh` now `unset PORT` first.
- **UI flow without a key (browser):** the page loads with no failed requests; Ctrl+Shift+K →
  **Inject** on Schema Drift makes `/api/chaos` show `schema-drift`, and alerts fire within
  seconds: gateway error rate (21% rising to 50%) and trade-confirmations (23% → 51%), which reads
  the same ref-data field. **Investigate** shows "no API key is configured. Put
  ANTHROPIC_API_KEY in the .env file at the repo root and restart", and the budget counter stays
  at 0. **Restore all** clears the fault, and alerts stayed at zero for 25 s from 89 s after the
  restore.
- **README:** says live mode is not hosted (memory and per-investigation API cost) and that you
  run it locally with your own key; documents both start routes (`run-local.sh` and compose) and
  the keyless behaviour; moves the hosting recipes under "Hosting it yourself (optional, not
  maintained)".

## Free local model option: Ollama (2026-10-20)

The owner asked for a free alternative to the paid Claude API. Added `LLM_PROVIDER=ollama`:

- `OllamaClient` implements the existing `LlmClient` interface. The agent loop still speaks the
  Anthropic Messages format; the client translates each request to Ollama's **native**
  `/api/chat` (system, assistant `tool_calls`, `tool` results, function tools) and the reply back to
  Anthropic-style `text` / `tool_use` blocks with `stop_reason` and token usage. The native endpoint
  was chosen over Ollama's OpenAI-compatible one because only the native one can raise
  `num_ctx`; Ollama's default context is far too small for an investigation.
- `LlmConfig` picks the client from `LLM_PROVIDER` (default `anthropic`, unchanged behaviour).
  Local tokens cost 0 in the usage/cost figures. The UI labels the agent "Local agent via
  Ollama (model)" instead of "Claude agent".
- **Bug fixed on the Claude path at the same time:** when the tool budget ran out, the agent forced
  the report with `tool_choice: {type: "tool", name: "submit_report"}`. Per the current API docs,
  `claude-sonnet-5-5` (the default model) rejects a forced `tool_choice` with HTTP 400, so any real
  investigation that used all 12 tool calls would have failed instead of returning an inconclusive
  report. The wrap-up is now requested with a text instruction on the last turn (`FORCE_REPORT`).
  `LlmInvestigatorTest.toolBudgetIsEnforcedAndTheReportIsForced` was changed to detect that
  instruction instead of `tool_choice`, and now also asserts that no request sends `tool_choice`.
  The test's purpose (budget enforced, report forced) is unchanged.
- Tests: `OllamaClientTest` (6): request translation, reply translation (including `<think>` blocks
  and string-encoded arguments), a call against a fake Ollama server, a clear "ollama pull <model>"
  error for a missing model, a clear "is it running?" error, and not billed. Backend: 37/37 pass.
- **Not yet done: a real investigation on a local model.** Ollama is not installed on this machine
  (RTX 2050 with 4 GB VRAM, 16 GB RAM). Installing it needs the owner's OK.

### First real investigations on a local model (2026-10-20/21): none completed

Setup: Ollama 0.40.6 (installed with winget, with the owner's OK), `qwen3:4b` (2.5 GB),
`LLM_PROVIDER=ollama`, `num_ctx` 16384, `think` off. Machine: i5-12450H, RTX 2050 4 GB, 16 GB RAM,
the whole live stack on the same machine via `scripts/run-local.sh`. Fault: `schema-drift`; the
investigation was started from the UI with **Investigate** on the gateway error-rate alert.
Ollama reported the model at 5.4 GB with a 16k context, split 56% CPU / 44% GPU.

| Run | Model calls | Tool calls | Result | Per-call time (Ollama log) |
|---|---|---|---|---|
| 1 | 2 (+1 hung) | 2 | FAILED: 300 s timeout | 50 s, 3m29s, 1m32s, then **10h40m**: the laptop slept mid-call |
| 2 | 6 (+1) | 5 | FAILED: 300 s timeout | 1m25s, 1m33s, 3m45s, 4m22s, 4m05s, 3m40s, then 5m00s timeout |
| 3 | 1 (+1) | 1 | FAILED: 900 s timeout | 46 s, then 24m00s wall clock; the machine was probably suspended part of that time (the app's 15-min timer does not count suspended time) |
| 4 | 5 (+1) | 5 | FAILED: 900 s timeout | 1m11s, 2m52s, 3m01s, 3m43s, 5m06s, then 15m00s with the GPU in P3 (210 MHz, 2% use) |

What worked: tool calls translated correctly in both directions; the estate tools returned real
evidence (gateway 100% errors, submission-builder clean, ref-data release 2.4.1 in the change
log); hypotheses, node states and "Local agent via Ollama (qwen3:4b)" showed in the UI; cost $0.
In run 2 the model's top hypothesis after its first evidence was the true cause ("ref-data-service
deployment 2.4.1 (currency DTO …)", 80%). No run reached a report, so there is **no accuracy figure**.

What did not: `qwen3:4b` writes 600-1,100 tokens of reasoning prose per turn even with thinking off,
at about 14 tokens/s, and turns grow slower as the conversation grows. An investigation needs
about 12-15 turns, i.e. 45-75 minutes even when the GPU is cooperating, and the GPU sometimes drops
to a low-power state (run 4), after which a turn takes 15+ minutes.

Changes made from these runs:
- `fixedRate` → `fixedDelay` for every estate scheduler (committed separately): after the night's
  sleep, trade-capture replayed every missed tick (~28k trades/min) and swamped the estate.
- Ollama requests now end the system prompt with a brevity instruction (`OllamaClient.BREVITY`).
  First-turn output fell from 544 to 334-380 tokens; later turns stayed at about 900 tokens.
- Default `OLLAMA_TIMEOUT_SECONDS` 300 → 900. Kept the clear timeout message.
- Kept the computer awake for the session (it still slept/slowed once, see run 3).

Conclusion: on this laptop the local option is not practical for full investigations. Options are
in the README's Ollama section.
