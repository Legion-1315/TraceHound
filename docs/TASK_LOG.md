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
