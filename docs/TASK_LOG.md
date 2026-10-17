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
