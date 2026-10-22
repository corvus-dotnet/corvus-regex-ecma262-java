# The benchmark harness as an image: this library built with javac, java.util.regex of the same JDK, GraalVM's Node for
# the TRegex rows and Node 24 for the V8 rows, with tools/benchmarks/run.sh as the entry point. The whole invocation is
#
#   docker run --rm -v <workloads.json>:/workloads.json:ro -v <texts dir>:/texts:ro -v <out dir>:/out <image>
#
# which writes /out/industry.json, the run's data file in the results page's shape. The ECMA-262 test data is cloned
# at depth 1 inside the container unless its data/ directory is mounted at /data. JOB=short, SECONDS_EACH, PASSES,
# CORES, SETTLE, V8 and RUN_URL pass through (see tools/benchmarks/run.sh). The central benchmark site runs this image
# each night; .github/workflows/harness-image.yml builds, smoke-runs and publishes it.
FROM node:24.11.0-bookworm-slim AS node

FROM eclipse-temurin:25.0.4_7-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends python3 git curl ca-certificates util-linux \
    && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
# GraalVM's Node 24, whose JavaScript engine is GraalJS and whose RegExp is TRegex: the TRegex rows.
ARG GRAALJS=25.4.4.1.1
ARG GRAALJS_SHA256=4736908e534ae5cc19e0e5e3e8282f0241ca0d64d12f3c596b405a8ea01a604e
RUN curl -sSfL -o /tmp/graalnode.tar.gz \
      "https://github.com/oracle/graaljs/releases/download/graal-$GRAALJS/graalnode24-community-$GRAALJS-linux-amd64.tar.gz" \
    && echo "$GRAALJS_SHA256  /tmp/graalnode.tar.gz" | sha256sum -c - \
    && mkdir -p /opt/graalnode && tar -xzf /tmp/graalnode.tar.gz -C /opt/graalnode --strip-components 1 \
    && rm /tmp/graalnode.tar.gz && ln -s /opt/graalnode/bin/node /usr/local/bin/graalnode
WORKDIR /harness
COPY . .
RUN tools/benchmarks/build.sh
# The commit the image is built from, for the run's record (the image has no .git).
ARG COMMIT=""
ENV SKIP_BUILD=1 CORVUS_COMMIT=$COMMIT
ENTRYPOINT ["/harness/tools/benchmarks/container-entry.sh"]
