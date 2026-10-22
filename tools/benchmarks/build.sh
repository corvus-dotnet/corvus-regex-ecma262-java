#!/usr/bin/env bash
# Builds the library and the harness's classes into target/harness (lib and tools), with javac alone: the library has
# no dependencies. run.sh calls it, and the image (Dockerfile) calls it when it is built.
set -euo pipefail
cd "$(dirname "$0")/../.."
classes=target/harness
rm -rf "$classes" && mkdir -p "$classes/lib" "$classes/tools"
find src/main/java -name '*.java' > "$classes/sources.txt"
javac --release 17 -encoding UTF-8 -d "$classes/lib" @"$classes/sources.txt"
javac -encoding UTF-8 -d "$classes/tools" -cp "$classes/lib" tools/benchmarks/Industry.java tools/dataset/Dataset.java
