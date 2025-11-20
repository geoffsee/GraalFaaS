#!/usr/bin/env bash

set -euo pipefail

# This script fetches public threat feeds, merges them, and builds a compressed
# binary blocklist (TRI1 Patricia trie) at project root: ./blocklist.bin
# It uses the utils CLI (BlocklistCli) via Gradle Toolchains (no local JDK needed).

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOGFILE="$ROOT_DIR/blocklist_update.log"
WORKDIR="$ROOT_DIR/blocklist_tmp"
OUTFILE="$ROOT_DIR/blocklist.bin"
DATE="$(date '+%Y-%m-%d %H:%M:%S')"

mkdir -p "$WORKDIR"
echo "[$DATE] Starting blocklist build..." | tee -a "$LOGFILE"

SOURCES=(
  "https://rules.emergingthreats.net/fwrules/emerging-Block-IPs.txt"
  "https://www.spamhaus.org/drop/drop.txt"
  "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_level1.netset"
  "https://lists.blocklist.de/lists/all.txt"
)

ARGS=("$OUTFILE")
for s in "${SOURCES[@]}"; do
  ARGS+=("--source" "$s")
done

echo "[$DATE] Building compressed trie to $OUTFILE ..." | tee -a "$LOGFILE"
(
  cd "$ROOT_DIR"
  ./gradlew -q :utils:run --args="${ARGS[*]}"
) 2>&1 | tee -a "$LOGFILE"

if [[ -f "$OUTFILE" ]]; then
  SIZE=$(stat -f%z "$OUTFILE" 2>/dev/null || stat -c%s "$OUTFILE") || true
  echo "[$DATE] Updated $OUTFILE successfully (${SIZE:-unknown} bytes)." | tee -a "$LOGFILE"
else
  echo "[$DATE] ERROR: $OUTFILE not created." | tee -a "$LOGFILE"
  exit 1
fi

echo "[$DATE] Cleaning up temp workdir..." | tee -a "$LOGFILE"
rm -rf "$WORKDIR"
echo "[$DATE] Blocklist build complete." | tee -a "$LOGFILE"

# Scheduling example (cron):
#  # Run daily at 03:15
#  15 3 * * * /bin/bash -lc 'cd "$ROOT_DIR" && scripts/generate_blocklist.sh'
