#!/usr/bin/env bash
# The benchmark harness, which the central benchmark site runs each night (as the image, Dockerfile) and anyone can run:
# it builds the library and the harness, works out the compliance of each ECMA-262 engine over the test data, runs
# every engine over the Industry workloads, and writes the run's one data file in the results page's shape.
#
#   tools/benchmarks/run.sh <workloads.json> <texts directory> <out.json> [test data directory]
#
# It needs a JDK (17 or later) and Python 3, Node 24 on PATH for the V8 rows, and GraalVM's Node (TREGEX_NODE, or
# graalnode on PATH) for the TRegex rows.
# - workloads.json: the Industry workloads ({name, pattern, flags, file, id}), as tools/benchmarks/v8/workloads.py
#   writes them from the .NET reference engine's Industry.cs (tools/benchmarks/workloads.json is a copy).
# - texts directory: the .txt.gz files the workloads name (those of the .NET benchmark); a file not there is fetched
#   from dotnet/performance into it.
# - out.json: the data file (meta, vocab, measures, impls, schemas, results; see tools/benchmarks/industry-results.py).
# - test data directory: the data/ directory of github.com/corvus-dotnet/ecma262-regex-test-data; without it the
#   repository is cloned at depth 1.
#
# Optional settings: ROUNDS (3), SECONDS_EACH (0.7) and PASSES (7) of the interleaved protocol and SPREAD (0.05), the
# spread over which a figure is listed, or JOB=short for a quick look (3 rounds of 0.1 s, one pass); CORES, a taskset
# core list to run the timed parts on; SETTLE, seconds to wait between the builds and the timed parts (0; 180 for a
# measured run on a quiet machine); SKIP_BUILD=1 when the classes are built (the image); V8=0 for no V8 rows (the
# central run, for every image but one), whose counts the workloads carry (v8Count); TREGEX_COMPLIANCE=1 to work out
# TRegex's compliance again (tools/benchmarks/tregex-compliance.py, twenty minutes) rather than take
# tools/benchmarks/compliance-tregex.txt; RUN_URL, the address of the run.
set -euo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
abs() { mkdir -p "$(dirname "$1")"; echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }
workloads="$(abs "${1:?workloads.json}")"
texts="$(mkdir -p "${2:?texts directory}" && cd "$2" && pwd)"
out_file="$(abs "${3:?out.json}")"
data="${4:-}"
. "$here/tools/benchmarks/protocol.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
timed() { if [ -n "${CORES:-}" ]; then taskset -c "$CORES" "$@"; else "$@"; fi; }
tregex_node="${TREGEX_NODE:-graalnode}"
cd "$here"
if [ -z "$data" ]; then
  git clone --quiet --depth 1 https://github.com/corvus-dotnet/ecma262-regex-test-data "$work/test-data"
  data="$work/test-data/data"
fi
classes=target/harness
# The image (Dockerfile) has them built already.
if [ "${SKIP_BUILD:-0}" != 1 ]; then tools/benchmarks/build.sh; fi
cp="$classes/lib:$classes/tools"
version="$(sed -n 's|^  <version>\(.*\)</version>|\1|p' pom.xml | head -n 1)"
java -Xss4m -cp "$cp" Dataset "$data" "$work/wrong-corvus.tsv" > "$work/compliance-corvus.txt" 2>&1 || true
v8=(--v8 tools/benchmarks/v8/industry.mjs)
compliance_v8=(--compliance-v8 "$work/compliance-v8.txt")
if [ "${V8:-1}" = 0 ]; then
  compliance_v8=()
  engines=(--engines corvus-utf8,corvus-utf16,jdk,tregex)
else
  node tools/benchmarks/v8/dataset.mjs "$data" > "$work/compliance-v8.txt" 2>&1 || true
  engines=()
fi
if [ "${TREGEX_COMPLIANCE:-0}" = 1 ]; then
  python3 tools/benchmarks/tregex-compliance.py "$(command -v "$tregex_node")" "$data" > "$work/compliance-tregex.txt" || true
else
  cp tools/benchmarks/compliance-tregex.txt "$work/compliance-tregex.txt"
fi
tail -n 1 "$work/compliance-corvus.txt" $([ -f "$work/compliance-v8.txt" ] && echo "$work/compliance-v8.txt") "$work/compliance-tregex.txt"
if [ "${SETTLE:-0}" -gt 0 ]; then
  echo "settling for $SETTLE s"
  sleep "$SETTLE"
fi
export CORVUS_REGEX_TEXTS="$texts" CORVUS_REGEX_PLAIN="$work/plain"
timed java -Dcorvus.version="$version" -cp "$cp" Industry "$workloads" "${v8[@]}" --tregex "$(command -v "$tregex_node")" \
  "${engines[@]}" --rounds "$rounds" --seconds "$seconds" --passes "$passes" --spread "$spread" --out "$work/run.json" \
  2> "$work/run.log"
grep -E '^(SPREAD|COUNT) ' "$work/run.log" || true
python3 tools/benchmarks/industry-results.py --workloads "$workloads" --run "$work/run.json" --out "$out_file" \
  --compliance-corvus "$work/compliance-corvus.txt" --compliance-tregex "$work/compliance-tregex.txt" \
  "${compliance_v8[@]}" --run-url "${RUN_URL:-}"
if [ -n "${KEEP:-}" ]; then mkdir -p "$KEEP" && cp "$work"/*.json "$work"/*.txt "$work"/*.log "$KEEP"/; fi
