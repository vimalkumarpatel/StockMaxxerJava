#!/usr/bin/env bash
# One simulated trading day. Each step is a separate run of the jobs jar, so every subsystem can also
# run on its own:  java -jar stockmaxxer-jobs/target/stockmaxxer-jobs.jar --job=<name>
#
# Order matters: today's bars must exist before accepted trades fill at today's open (settle),
# before NAV is marked at today's close (mark), and before tomorrow's recommendations are made (propose).
set -euo pipefail
cd "$(dirname "$0")/.."      # one database file: always run from the repo root
JAR=stockmaxxer-jobs/target/stockmaxxer-jobs.jar
[ -f "$JAR" ] || { echo "missing $JAR: run 'mvn -q package' first" >&2; exit 1; }

for job in advance prices expire settle mark propose; do
  java -jar "$JAR" --job="$job"
done
