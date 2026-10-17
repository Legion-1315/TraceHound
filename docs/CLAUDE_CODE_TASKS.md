# TraceHound live mode: pending work for Claude Code

Read `CLAUDE.md` and `README.md` first, then work through the tasks below **in order**.
Keep a short log of what you did and what you found in `docs/TASK_LOG.md` (create it).

## Context

- Repo: `https://github.com/Legion-1315/TraceHound`, branch **`feature/live-estate`**.
  `master` is the original hackathon demo (scripted mode) and is what Render deploys. Do not
  push to `master` and do not change `render.yaml` unless the owner asks.
- Three Gradle/npm projects:
  - `estate/`: six real Spring Boot services plus a mock regulator. **One jar**, and the Spring
    profile picks the role: `trade-capture` (8101), `ref-data-service` (8102),
    `enrichment-service` (8103), `submission-builder` (8104), `regulatory-gateway` (8105),
    `trade-confirmations` (8106), `regulator-arm` (8443, mutual TLS).
  - `backend/`: the triage app (Spring Boot, Java 21). It has two estates: `TRIAGE_ESTATE=simulated`
    (original demo) and `TRIAGE_ESTATE=live` (investigates the services in `estate/` with a real
    Claude agent: `agent/LlmInvestigator.java`, `llm/AnthropicHttpClient.java`, `llm/LlmBudget.java`).
  - `frontend/`: React + TypeScript + Vite. The Gradle build bundles it into the backend jar.
- Live faults: `backend/src/main/resources/live-faults.yaml`. The ids are `schema-drift`,
  `consumer-lag`, `cert-expiry`, `latency-spike`, `duplicate-trades`, `thread-pool-exhaustion`.
  Inject one with `POST /api/chaos/{id}` and clear all with `POST /api/chaos/none`.
- Start the whole live demo with `docker compose up --build`, then open http://localhost:8080.

## Hard rules

1. **Secrets.** The Anthropic key lives only in `.env` at the repo root, which is git-ignored. Never
   print it, echo it, log it, paste it into a file that is tracked, or put it in a commit, a test
   fixture or a command line that ends up in shell history. Before every commit, run
   `git check-ignore .env` (it must print `.env`) and
   `git diff --cached | grep -E "sk-ant-"` (it must print nothing).
2. **Spend.** Every real LLM call costs the owner money. Run the expensive steps (task 4 and
   task 5) only when the owner says "go". Keep the defaults in `.env.example`
   (US$5/day cap). Never raise limits on your own.
3. **No fake results.** Never write accuracy numbers, timings or costs that you did not measure in
   this repo. If something fails, report it as a failure.
4. Commit after each task with a clear message, on `feature/live-estate`, and push. No force-push.
   Keep CI (`.github/workflows/ci.yml`) green.
5. On Windows use `gradlew.bat` instead of `./gradlew`.

## Task 0: get the branch

```bash
git status                       # if there are local changes, stop and ask the owner what to do
git remote set-url origin https://github.com/Legion-1315/TraceHound.git
git fetch origin
git checkout feature/live-estate
git pull
```

## Task 1: compile and run every test

```bash
cd estate   && ./gradlew test bootJar --no-daemon && cd ..
cd backend  && ./gradlew test --no-daemon && cd ..        # this also builds the React app
cd frontend && npm ci && npm run build && npx oxlint src && cd ..
```

Fix every compile error, test failure and lint error. Do not delete or weaken a test to make it
pass; fix the code, or explain in the log why the test itself was wrong.

## Task 2: write the missing smoke test (no LLM, no key needed)

CI has a job called "Live estate smoke test", but **`scripts/smoke-test.sh` does not exist**, so
that job currently passes without testing anything. Write it:

1. Start all seven estate services from `estate/build/libs/*.jar` (one `java -jar` per profile,
   using the ports above). Then start the backend jar with `TRIAGE_ESTATE=live` and **no**
   `ANTHROPIC_API_KEY`. Write logs to `.run/logs/<service>.log`, which the CI step already collects.
2. Wait until `GET /api/estate/health` reports every service up, with a 120 s timeout.
3. For each of the six fault ids: `POST /api/chaos/{id}`, wait, and assert that the expected symptom
   is visible through the backend's own read paths. Examples: higher error rate or latency on the
   right service, a TLS handshake failure in the gateway logs, the alert monitor raising an alert
   (`GET /api/alerts`). Then `POST /api/chaos/none` and check that the estate recovers.
4. Exit non-zero on any failure, and always kill the processes it started.

Make it pass locally and in CI. Also remove the `if [ ! -f scripts/smoke-test.sh ]` shortcut from
the CI step, so a missing script fails the build.

## Task 3: security fixes before anything goes public

1. `POST /api/chaos/admin/clear-history` in `backend/.../api/ApiControllers.java` has no
   authentication. On a public demo, any visitor could wipe evidence in the middle of someone
   else's investigation. Protect it with a token: add a `TRIAGE_ADMIN_TOKEN` env var and require it
   in a header. If the token is unset, the endpoint returns 404. Add a test.
2. Check the pricing used for the cost estimate: `triage.llm.price-input-per-mtok` and
   `price-output-per-mtok` in `application.properties` are placeholders (2.0 / 10.0). Look up the
   current price for the configured model (`ANTHROPIC_MODEL`, default `claude-sonnet-5-5`) on the
   official Anthropic pricing page and update the defaults. Confirm that the model id is valid
   too. Put the source link in a comment.
3. Confirm the estate services' `/chaos` endpoints are not published to the host in
   `docker-compose.yml` (only the triage app, on 8080).

## Task 4: first real end-to-end run with the LLM (ask the owner first)

Prerequisite: the owner has created `.env` from `.env.example` with their key.

1. `docker compose up --build`. Wait for `GET /api/estate/health` to report all services up.
2. For each of the six faults: inject it, start an investigation with a realistic alert (or let
   the alert monitor raise one), and wait for the report. Record the outcome, the diagnosed root
   cause, tool calls, time taken, and tokens/cost from the report's usage section.
3. Negative checks:
   - With no fault injected, the alert "error rate high on regulatory-gateway" should end
     `NO_FAULT_FOUND`.
   - An unrelated alert, e.g. "my laptop wifi is slow", should end `OUT_OF_SCOPE`.
4. Rate limit: the 6th investigation from the same client within an hour should get HTTP 429.
5. UI: in the browser, check that the canvas, hypotheses, report, replay and footer usage numbers
   update during a live run.

Fix the bugs you find. Write the results table into `docs/TASK_LOG.md`.

## Task 5: LLM evaluation harness (ask the owner first; this costs money)

Build an opt-in harness, for example `scripts/eval-llm.sh`, or a JUnit test tagged `llm-eval`
that is skipped unless `RUN_LLM_EVAL=true`. It never runs in normal CI. For each fault × N runs
(default N=3, so 18 investigations; estimate the cost first and print it):

1. Run `POST /api/chaos/admin/clear-history` (with the admin token), then inject the fault.
2. Start an investigation and wait for the report.
3. Grade it: is the diagnosed root-cause service and cause correct? Use `expected_root_cause` from
   `live-faults.yaml` if it is there; otherwise add an expected service and keywords per fault.
4. Reset the faults.

Write `docs/eval-results.md` with per-fault and overall accuracy, median tool calls, median
latency, and total cost. Include the model id, date and N. Only real measured numbers.

## Task 6: polish

- The frontend bundle is about 840 kB, and Vite warns about it. Code-split the heavy views
  (React Flow canvas, Recharts analytics) with `React.lazy`/dynamic `import()`.
- In `README.md`, make sure the "Live mode" and "Oracle Cloud" sections match what you actually ran.
  Update "Honest limitations" with anything you learned in tasks 4 and 5.
- Fix any CI warnings.

## Done when

- [ ] Tasks 1 to 3 committed and pushed; CI green, including a real smoke test
- [ ] Task 4 results in `docs/TASK_LOG.md` (after the owner said go)
- [ ] Task 5 results in `docs/eval-results.md` (after the owner said go)
- [ ] `git log -p | grep -E "sk-ant-"` prints nothing
- [ ] A short summary for the owner: what was fixed, what still fails, and what needs their decision
  (for example: merging to `master`, deploying to the Oracle VM)
