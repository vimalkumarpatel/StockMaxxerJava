#!/usr/bin/env bash
# Frees the app's ports: stops whatever listens on them (default 8080 for the API, 3000 for the UI),
# including a leftover `npm run dev` / `next dev` parent. SIGTERM first, so Spring and Next shut down
# cleanly and SQLite checkpoints its WAL; SIGKILL whatever still listens after 15 seconds.
#
# Usage: scripts/stop.sh [port ...]
set -euo pipefail
ports=("$@")
[ $# -gt 0 ] || ports=(8080 3000)

listeners() {
  for port in "${ports[@]}"; do lsof -t -nP -iTCP:"$port" -sTCP:LISTEN || true; done | sort -u
}

pids=$(listeners)
[ -n "$pids" ] || { echo "nothing listens on ${ports[*]}"; exit 0; }

# The UI's listener is next-server; its `next dev` and `npm run dev` parents would otherwise linger.
for pid in $pids; do
  parent=$(ps -o ppid= -p "$pid" | tr -d ' ')
  while [ -n "$parent" ] && [ "$parent" -gt 1 ] && ps -o command= -p "$parent" | grep -qE 'npm run dev|next dev'; do
    pids="$pids $parent"
    parent=$(ps -o ppid= -p "$parent" | tr -d ' ')
  done
done

echo "stopping:"
ps -o pid=,command= -p "$(echo $pids | tr ' ' ,)"
kill $pids 2>/dev/null || true

for _ in $(seq 15); do
  [ -z "$(listeners)" ] && { echo "ports ${ports[*]} are free"; exit 0; }
  sleep 1
done
left=$(listeners)
echo "still listening after 15 s, killing: $left"
kill -9 $left
