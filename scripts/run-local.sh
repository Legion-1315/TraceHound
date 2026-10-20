#!/usr/bin/env bash
# Run the whole live demo on this machine without Docker: the six estate services, the mock
# regulator and the triage app, each as a plain java process. Needs Java 21+ only.
#
#   bash scripts/run-local.sh            # builds the jars if missing, then open http://localhost:8080
#   bash scripts/run-local.sh --rebuild  # rebuild both jars first
#
# The triage app reads ANTHROPIC_API_KEY (and the other settings) from .env in the repo root,
# which is git-ignored. Without a key the estate, faults, alerts and UI all work, but
# investigations cannot start. Logs go to .run/logs/<service>.log. Ctrl+C stops everything.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
LOG_DIR="$ROOT/.run/logs"
# Every estate profile reads server.port from ${PORT:<its default>}, so an inherited PORT (set by
# PaaS shells and some launchers) would send all seven services to the same port.
unset PORT

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) GRADLE=./gradlew.bat ;; *) GRADLE=./gradlew ;; esac

jar_in() { ls "$ROOT/$1"/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -n 1 || true; }

if [ "${1:-}" = "--rebuild" ] || [ -z "$(jar_in estate)" ] || [ -z "$(jar_in backend)" ]; then
  echo "=== building jars (first build takes a few minutes)"
  (cd estate && $GRADLE bootJar --no-daemon -q)
  (cd backend && $GRADLE bootJar --no-daemon -q)
fi
ESTATE_JAR="$(jar_in estate)"
TRIAGE_JAR="$(jar_in backend)"

for port in 8080 8101 8102 8103 8104 8105 8106 8443; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    echo "Port $port is already in use. Stop whatever is running there (another run, docker compose) first."
    exit 1
  fi
done

if [ -f .env ] && grep -q '^ANTHROPIC_API_KEY=..*' .env; then
  echo "=== .env has an API key: live investigations are enabled (spend caps from .env apply)"
else
  echo "=== no API key in .env: the estate, faults, alerts and UI work; investigations will not start"
fi

mkdir -p "$LOG_DIR" "$ROOT/data"
PIDS=()
stop() {
  echo
  echo "=== stopping ${#PIDS[@]} processes"
  for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
  sleep 2
  for pid in "${PIDS[@]}"; do kill -9 "$pid" 2>/dev/null || true; done
}
trap stop EXIT INT TERM

JVM_OPTS=(-Xms64m -Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1)
for p in regulator-arm ref-data-service trade-confirmations regulatory-gateway submission-builder \
         enrichment-service trade-capture; do
  java "${JVM_OPTS[@]}" -jar "$ESTATE_JAR" --spring.profiles.active="$p" >"$LOG_DIR/$p.log" 2>&1 &
  PIDS+=($!)
done

# Runs from the repo root so the app's optional ./.env import finds the key.
TRIAGE_ESTATE=live java -Xms64m -Xmx512m -jar "$TRIAGE_JAR" >"$LOG_DIR/triage.log" 2>&1 &
PIDS+=($!)

echo "=== waiting for the triage app and all six services"
for i in $(seq 1 60); do
  if curl -sf http://localhost:8080/api/estate/health 2>/dev/null | grep -q '"DOWN"'; then :;
  elif curl -sf http://localhost:8080/api/estate/health >/dev/null 2>&1; then
    echo "=== ready: open http://localhost:8080  (chaos panel: Ctrl+Shift+K; Ctrl+C here stops everything)"
    break
  fi
  [ "$i" -eq 60 ] && { echo "Not ready after 120 s; see $LOG_DIR"; exit 1; }
  sleep 2
done

wait
