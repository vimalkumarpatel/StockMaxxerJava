#!/usr/bin/env bash
# A team demo: replay real trading days one at a time. Each day: the night's jobs run, every portfolio
# accepts its new recommendation (standing in for a person clicking Accept), and a report prints what
# happened. Offline: prices, filings and Claude's answers all come from what scripts/fetch.sh stored.
#   scripts/demo.sh [days] [pause-seconds]      e.g. scripts/demo.sh 10 5
set -euo pipefail
cd "$(dirname "$0")/.."      # one database file: always run from the repo root
JAR=stockmaxxer-jobs/target/stockmaxxer-jobs.jar
[ -f "$JAR" ] || { echo "missing $JAR: run 'mvn -q package' first" >&2; exit 1; }
DAYS=${1:-10}
PAUSE=${2:-5}
export SM_LLM_REPLAY=1       # never call Claude during a demo: a cache miss is an error

for ((i = 1; i <= DAYS; i++)); do
  for job in advance prices expire settle mark propose accept report; do
    java -jar "$JAR" --job="$job"
  done
  sleep "$PAUSE"
done
