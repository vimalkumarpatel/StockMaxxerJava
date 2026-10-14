#!/usr/bin/env bash
# A team demo: replay real trading days one at a time. Each day: the night's jobs run, every portfolio
# accepts its new recommendation (standing in for a person clicking Accept), and a report prints what
# happened. Offline: prices, filings and Claude's answers all come from what scripts/fetch.sh stored.
#   scripts/demo.sh [days] [pause-seconds]      e.g. scripts/demo.sh 10 5
set -euo pipefail
cd "$(dirname "$0")/.."      # one database file: always run from the repo root
JAR=stockmaxxer-jobs/target/stockmaxxer-jobs.jar
[ -f "$JAR" ] || { echo "missing $JAR: run 'mvn -q package' first" >&2; exit 1; }
DB=${SM_DB_PATH:-data/stockmaxxer.sqlite}
if [ ! -f "$DB" ] || [ -z "$(sqlite3 "$DB" 'SELECT 1 FROM history LIMIT 1' 2>/dev/null)" ]; then
  echo "no price history in $DB: prepare the demo first (README, 'Demo to a team', step 1: seed, then scripts/fetch.sh)" >&2
  exit 1
fi
DAYS=${1:-10}
PAUSE=${2:-5}
export SM_LLM_REPLAY=1       # never call Claude during a demo: a cache miss is an error

for ((i = 1; i <= DAYS; i++)); do
  for job in advance prices expire settle mark propose accept report; do
    java -jar "$JAR" --job="$job"
  done
  sleep "$PAUSE"
done
