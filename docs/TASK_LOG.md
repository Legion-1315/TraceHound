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
