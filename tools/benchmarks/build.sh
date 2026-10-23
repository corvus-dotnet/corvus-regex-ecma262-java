#!/usr/bin/env bash
# Builds the library and the harness's classes into target/harness (lib and tools), with javac alone: the library has
# no dependencies. It needs JDK 25's javac (for -Xlint:-incubating). run.sh calls it, and the image (Dockerfile) calls it
# when it is built.
set -euo pipefail
cd "$(dirname "$0")/../.."
classes=target/harness
rm -rf "$classes" && mkdir -p "$classes/lib" "$classes/tools"
find src/main/java -name '*.java' > "$classes/sources.txt"
# The Vector API's searches (VectorBytes) compile against the incubating module; JDK 25's javac names its warning.
javac --release 17 --add-modules jdk.incubator.vector -Xlint:-incubating -encoding UTF-8 -d "$classes/lib" @"$classes/sources.txt"
javac -encoding UTF-8 -d "$classes/tools" -cp "$classes/lib" tools/benchmarks/Industry.java tools/dataset/Dataset.java
