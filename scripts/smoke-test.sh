#!/usr/bin/env bash
# Live estate smoke test: real services, real faults, no LLM and no API key.
#
# Starts the seven estate services and the triage app (TRIAGE_ESTATE=live) from the built jars,
# injects each live fault through the triage app's chaos API, checks that the expected symptom shows
# up in the triage app's own read paths (/api/alerts, /api/chaos, /api/estate/health) and in the
# affected service's logs, then clears the fault and waits for the estate to recover.
#
#   cd estate  && ./gradlew bootJar
#   cd backend && ./gradlew bootJar
#   bash scripts/smoke-test.sh            # from the repo root; logs go to .run/logs/<service>.log
#
# Exits non-zero on the first failed check. Always stops the processes it started.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RUN_DIR="$ROOT/.run"
LOG_DIR="$RUN_DIR/logs"
TRIAGE="http://localhost:8080"
SYMPTOM_TIMEOUT="${SMOKE_SYMPTOM_TIMEOUT:-150}"
RECOVERY_TIMEOUT="${SMOKE_RECOVERY_TIMEOUT:-240}"

# Pick a working Python for JSON parsing (python3 on CI; the Windows Store stub of python3 does not run).
PY=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "pass" >/dev/null 2>&1; then
    PY="$candidate"
    break
  fi
done
[ -n "$PY" ] || { echo "FAIL: python3 is required to parse JSON"; exit 2; }
# Windows Python writes CRLF; strip the CR so line matches like ':UP$' work everywhere.
py() { "$PY" "$@" | tr -d '\r'; }

jar_in() {
  # the Spring Boot fat jar, not the -plain one
  ls "$ROOT/$1"/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -n 1
}
ESTATE_JAR="$(jar_in estate)"
TRIAGE_JAR="$(jar_in backend)"
[ -n "$ESTATE_JAR" ] || { echo "FAIL: no jar in estate/build/libs (run ./gradlew bootJar in estate/)"; exit 2; }
[ -n "$TRIAGE_JAR" ] || { echo "FAIL: no jar in backend/build/libs (run ./gradlew bootJar in backend/)"; exit 2; }

rm -rf "$LOG_DIR" "$RUN_DIR/triage"
mkdir -p "$LOG_DIR" "$RUN_DIR/triage"

PIDS=()
cleanup() {
  local code=$?
  if [ "${#PIDS[@]}" -gt 0 ]; then
    echo "--- stopping ${#PIDS[@]} processes"
    for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
    sleep 2
    for pid in "${PIDS[@]}"; do kill -9 "$pid" 2>/dev/null || true; done
  fi
  if [ "$code" -eq 0 ]; then echo "SMOKE TEST PASSED"; else echo "SMOKE TEST FAILED (exit $code)"; fi
  exit "$code"
}
trap cleanup EXIT INT TERM

fail() {
  echo "FAIL: $*"
  echo "--- /api/estate/health:"; curl -s -m 10 "$TRIAGE/api/estate/health" || true; echo
  echo "--- /api/alerts:"; curl -s -m 10 "$TRIAGE/api/alerts" || true; echo
  exit 1
}

JVM_OPTS=(-Xms64m -Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1)

start_estate() {
  local profile=$1
  java "${JVM_OPTS[@]}" -jar "$ESTATE_JAR" --spring.profiles.active="$profile" \
    >"$LOG_DIR/$profile.log" 2>&1 &
  PIDS+=($!)
  echo "started $profile (pid $!)"
}

echo "=== starting the estate"
for p in regulator-arm ref-data-service trade-confirmations regulatory-gateway submission-builder \
         enrichment-service trade-capture; do
  start_estate "$p"
done

echo "=== starting the triage app (live estate, no API key)"
# Run from an empty directory so the app's optional .env import (./.env, ../.env) finds nothing,
# and blank the key explicitly in case one is set in this shell.
(
  cd "$RUN_DIR/triage"
  exec env TRIAGE_ESTATE=live ANTHROPIC_API_KEY= \
    TRIAGE_MEMORY_FILE="$RUN_DIR/triage/incident-memory.json" \
    TRIAGE_HISTORY_FILE="$RUN_DIR/triage/incident-history.json" \
    java -Xms64m -Xmx512m -XX:TieredStopAtLevel=1 -jar "$TRIAGE_JAR"
) >"$LOG_DIR/triage.log" 2>&1 &
PIDS+=($!)
echo "started triage (pid $!)"

# ---- JSON helpers (stdin = JSON) ---------------------------------------------------------------

# prints "service:STATUS" per service
health_lines() {
  py -c '
import json, sys
d = json.load(sys.stdin)
for k, v in d.items():
    print(k + ":" + (str(v.get("status", "?")) if isinstance(v, dict) else str(v)))'
}

# prints "service ruleId" per active alert
alert_lines() {
  py -c '
import json, sys
for a in json.load(sys.stdin):
    print(a["serviceId"], a["ruleId"])'
}

json_field() {
  py -c "import json,sys; v=json.load(sys.stdin).get('$1'); print(','.join(v) if isinstance(v, list) else ('' if v is None else v))"
}

# ---- polling ------------------------------------------------------------------------------------

# wait_until <timeout-seconds> <description> <command...>: retries the command every 3 s.
wait_until() {
  local timeout=$1 what=$2
  shift 2
  local start=$SECONDS
  while true; do
    if "$@" >/dev/null 2>&1; then
      echo "  ok after $((SECONDS - start))s: $what"
      return 0
    fi
    if [ $((SECONDS - start)) -ge "$timeout" ]; then
      fail "timed out after ${timeout}s waiting for: $what"
    fi
    sleep 3
  done
}

all_up() {
  local out
  out="$(curl -sf "$TRIAGE/api/estate/health" | health_lines)" || return 1
  [ "$(echo "$out" | grep -c ':UP$')" -eq 6 ] && ! echo "$out" | grep -qv ':UP$'
}

has_alert() { # <service> <ruleId>
  curl -sf "$TRIAGE/api/alerts" | alert_lines | grep -qx "$1 $2"
}

no_alerts() {
  local out
  out="$(curl -sf "$TRIAGE/api/alerts" | alert_lines)" || return 1
  [ -z "$out" ]
}

log_has() { # <port> <level> <regex>: a matching line in the service's last 2 minutes of logs
  curl -sf "http://localhost:$1/ops/logs?minutes=2&level=$2&limit=5000" | grep -Eq "$3"
}

chaos() {
  local out
  out="$(curl -s -w '\n%{http_code}' -X POST "$TRIAGE/api/chaos/$1")"
  local code="${out##*$'\n'}"
  [ "$code" = "200" ] || fail "POST /api/chaos/$1 returned HTTP $code: ${out%$'\n'*}"
  echo "${out%$'\n'*}"
}

active_faults() {
  curl -sf "$TRIAGE/api/chaos" | json_field activeFaults
}

# ---- the test -----------------------------------------------------------------------------------

echo "=== waiting for every service to report UP (120 s)"
wait_until 120 "all six services UP in /api/estate/health" all_up

curl -sf "$TRIAGE/api/mode" | grep -q '"estate":"live"' || fail "/api/mode does not report the live estate"
curl -sf "$TRIAGE/api/mode" | grep -q '"llmConfigured":false' || fail "the triage app picked up an API key"

echo "=== warm-up: healthy estate must settle with no alerts (throughput rules need 90 s of uptime)"
sleep 30
wait_until "$RECOVERY_TIMEOUT" "healthy estate, no active alerts" no_alerts

PORT_TC=8101; PORT_RD=8102; PORT_EN=8103; PORT_SB=8104; PORT_GW=8105

check_fault() {
  local fault=$1
  echo "=== fault: $fault"
  chaos "$fault" >/dev/null
  [ "$(active_faults)" = "$fault" ] || fail "/api/chaos does not list $fault as active (got '$(active_faults)')"

  case "$fault" in
    schema-drift)
      wait_until "$SYMPTOM_TIMEOUT" "error-rate alert on regulatory-gateway" has_alert regulatory-gateway TH-ERR-001
      wait_until 30 "enrichment WARNs that 'ccy' is absent" log_has $PORT_EN WARN "source field 'ccy' absent"
      wait_until 30 "gateway rejects for missing currency" log_has $PORT_GW ERROR "mandatory field 'currency' missing"
      ;;
    consumer-lag)
      wait_until "$SYMPTOM_TIMEOUT" "low-throughput alert on enrichment-service" has_alert enrichment-service TH-VOL-001
      ;;
    cert-expiry)
      wait_until "$SYMPTOM_TIMEOUT" "error-rate alert on regulatory-gateway" has_alert regulatory-gateway TH-ERR-001
      wait_until 30 "TLS failure to the regulator in gateway logs" log_has $PORT_GW ERROR "TLS/transport error"
      ;;
    latency-spike)
      wait_until "$SYMPTOM_TIMEOUT" "p95 latency alert on ref-data-service" has_alert ref-data-service TH-LAT-001
      wait_until "$SYMPTOM_TIMEOUT" "p95 latency alert on enrichment-service" has_alert enrichment-service TH-LAT-001
      wait_until 30 "slow CCY_REF queries in ref-data logs" log_has $PORT_RD WARN "Slow query on CCY_REF"
      if has_alert enrichment-service TH-ERR-001; then fail "latency-spike should not raise errors on enrichment"; fi
      ;;
    duplicate-trades)
      wait_until "$SYMPTOM_TIMEOUT" "error-rate alert on regulatory-gateway" has_alert regulatory-gateway TH-ERR-001
      wait_until 30 "duplicate rejections in gateway logs" log_has $PORT_GW ERROR "duplicate transaction reference"
      wait_until "$SYMPTOM_TIMEOUT" "high-throughput alert on trade-capture" has_alert trade-capture TH-VOL-002
      ;;
    thread-pool-exhaustion)
      wait_until "$SYMPTOM_TIMEOUT" "error-rate alert on submission-builder" has_alert submission-builder TH-ERR-001
      wait_until 30 "sealing pool saturation in builder logs" log_has $PORT_SB ERROR "Sealing pool saturated"
      ;;
    *) fail "no symptom check written for fault $fault" ;;
  esac

  chaos none >/dev/null
  [ -z "$(active_faults)" ] || fail "faults still active after reset: $(active_faults)"
  wait_until "$RECOVERY_TIMEOUT" "estate recovers from $fault (no active alerts)" no_alerts
  wait_until 30 "all six services still UP" all_up
}

FAULTS=$(curl -sf "$TRIAGE/api/chaos" | py -c 'import json,sys; print(" ".join(s["id"] for s in json.load(sys.stdin)["scenarios"]))')
EXPECTED="schema-drift consumer-lag cert-expiry latency-spike duplicate-trades thread-pool-exhaustion"
[ "$FAULTS" = "$EXPECTED" ] || fail "fault catalogue changed: got '$FAULTS', smoke test covers '$EXPECTED'"

for f in $FAULTS; do
  check_fault "$f"
done


echo "=== clear-history must not exist without TRIAGE_ADMIN_TOKEN"
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$TRIAGE/api/chaos/admin/clear-history")"
[ "$code" = "404" ] || fail "POST /api/chaos/admin/clear-history with no token configured returned $code, expected 404"
echo "  ok: 404"
