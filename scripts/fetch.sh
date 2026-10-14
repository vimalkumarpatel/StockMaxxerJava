#!/usr/bin/env bash
# Ingest: download what is new from the network. Run it once after seed, and again only to catch up with
# real time. The nightly script stays offline: it only replays what this script stored.
set -euo pipefail
cd "$(dirname "$0")/.."      # one database file: always run from the repo root
JAR=stockmaxxer-jobs/target/stockmaxxer-jobs.jar
[ -f "$JAR" ] || { echo "missing $JAR: run 'mvn -q package' first" >&2; exit 1; }

for job in fetch filings signals; do
  java -jar "$JAR" --job="$job"
done
