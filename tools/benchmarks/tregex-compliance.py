#!/usr/bin/env python3
"""TRegex's compliance over the ECMA-262 test data: tools/benchmarks/v8/dataset.mjs run by GraalVM's Node, whose RegExp
is TRegex, with the limit of 2 seconds on each answer that every runner gives.

A JavaScript engine cannot be stopped inside a match, so dataset.mjs runs with DATASET_LIMIT=1: it writes each answer
to stderr as it gives it, and a watchdog thread in the same process times each answer from just before TRegex is
called and ends the process when one passes 2 seconds. That answer is counted unanswered ("limit", as the judging rule
of the data counts an answer an engine never gave), and a new process goes on from the next answer (DATASET_FROM),
so a record loses only the answer that did not finish. A process that ends on an answer without reaching the limit
is asked that answer once more, since GraalVM's Node now and then ends on an answer it gives on another run. Once three
answers of a record have reached the limit, its other answers are unanswered without being asked, so that a pattern
TRegex cannot answer on any text costs seconds, not one limit for each of its answers. The limit is the wall clock's (no JavaScript thread can read another's CPU
time), so the run is made with nothing else running on the machine. The output is the runner's: a line for each file and the last
line of counts, with each answer that reached the limit named.

    tregex-compliance.py <GraalVM's node> <data directory> [--compliance <file>]

With --compliance, TRegex's compliance object is written to the file, as the benchmark page reads it
(docs/implementing-a-source.md in corvus-regex-ecma262-benchmarks): the counts, this script and the calls dataset.mjs
makes, and what it leaves out: UTF-8 (a JavaScript string is UTF-16).

run.sh runs it with TREGEX_COMPLIANCE=1; otherwise it takes tools/benchmarks/compliance-tregex.json, this script's
compliance file, since a run takes some minutes (GraalJS compiles each pattern), where V8's takes seconds.
"""
import argparse, json, os, select, signal, subprocess, sys, time

ap = argparse.ArgumentParser()
ap.add_argument("node")
ap.add_argument("data")
ap.add_argument("--compliance")
args = ap.parse_args()
script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "v8", "dataset.mjs")
EXEC = "exec/test262.jsonl"
# How long a process may go without a word before it is taken for lost (its watchdog ends it within 2 s of an answer
# that does not finish; this catches one stuck anywhere else).
SILENCE = 60
# The answers of a record that may reach the limit before its other answers are unanswered without being asked.
LIMITS_PER_RECORD = 3

FIELDS = ["right", "wrong", "refused", "accepted", "limit", "records", "skipped"]
per_file = {}
limited = []
start = time.time()
engine = ""
at = None  # (file, record, answer) to start from: None for the beginning
at_limit = 0  # the answers of the record at `at` that reached the limit
retried = set()  # the answers asked again after the process ended on them
while True:
    env = dict(os.environ, DATASET_LIMIT="1")
    env.pop("DATASET_FROM", None)
    if at:
        env["DATASET_FROM"] = f"{at[0]}\t{at[1]}\t{at[2]}\t{1 if at_limit >= LIMITS_PER_RECORD else 0}"
    # In a process group of its own: dataset.mjs judges each file in a child process, so stopping the script alone
    # would leave that child running.
    child = subprocess.Popen([args.node, script, args.data], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             start_new_session=True)
    buf = ""
    last = time.time()
    # The record being judged and the number of its answers given so far.
    current, given = ((at[0], at[1]), at[2]) if at else ((None, None), 0)
    why = None
    while True:
        ready, _, _ = select.select([child.stderr], [], [], 1.0)
        if not ready:
            if time.time() - last > SILENCE:
                why = f"nothing written in {SILENCE} s"
                break
            continue
        chunk = os.read(child.stderr.fileno(), 65536).decode("utf-8", "replace")
        if not chunk:
            break
        last = time.time()
        buf += chunk
        *lines, buf = buf.split("\n")
        for line in lines:
            f = line.split("\t")
            if f[0] == "R":
                name, index = f[1], int(f[2])
                given = at[2] if at and (name, index) == (at[0], at[1]) else 0
                current = (name, index)
            elif f[0] == "S":
                per_file.setdefault(current[0], dict.fromkeys(FIELDS, 0))["skipped"] += 1
            elif f[0] == "C":
                c = per_file.setdefault(current[0], dict.fromkeys(FIELDS, 0))
                for key, value in zip(["right", "wrong", "refused", "accepted", "limit"], f[2:7]):
                    c[key] += int(value)
                given += int(f[1])
            elif f[0] == "E":
                per_file.setdefault(current[0], dict.fromkeys(FIELDS, 0))["records"] += 1
            elif f[0] == "L":
                why = "no answer in 2 s"
        if why:
            break
    if why is None:
        out = child.stdout.read().decode("utf-8", "replace")
        child.wait()
        if child.returncode == 0:
            for line in out.splitlines():
                if line.startswith("records ") and "(" in line:
                    engine = line[line.rindex("("):]
            break
        why = f"the process ended with {child.returncode}"
    # Its process group can be gone already (it ended, or it ended itself at the limit).
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    child.wait()
    if current[0] is None:
        raise SystemExit(f"dataset.mjs ended before its first record: {why}")
    # A process that ended on an answer (not at the limit) is asked that answer once more: GraalVM's Node now and then
    # ends with 1 on an answer it gives on another run, and only an answer that ends it again is unanswered.
    if why.startswith("the process ended") and (current[0], current[1], given) not in retried:
        retried.add((current[0], current[1], given))
        print(f"AGAIN {current[0]} record {current[1] + 1} answer {given + 1}: {why}", file=sys.stderr, flush=True)
        at = (current[0], current[1], given)
        continue
    # The answer being given is unanswered; the next process goes on from the one after it.
    per_file.setdefault(current[0], dict.fromkeys(FIELDS, 0))["limit"] += 1
    limited.append((current[0], current[1], given, why))
    print(f"LIMIT {current[0]} record {current[1] + 1} answer {given + 1}: {why}", file=sys.stderr, flush=True)
    if not at or (at[0], at[1]) != current:
        at_limit = 0
    at_limit += 1
    if at_limit == LIMITS_PER_RECORD:
        limited.append((current[0], current[1], given, f"its answers after this not asked: {LIMITS_PER_RECORD} at the limit"))
    at = (current[0], current[1], given + 1)

total = dict.fromkeys(FIELDS, 0)
for name in sorted(per_file, key=lambda n: (n == EXEC, n)):
    c = per_file[name]
    for key in FIELDS:
        total[key] += c[key]
    print(f"{name:<24} right {c['right']:>10}  wrong {c['wrong']:>6}  refused-valid {c['refused']:>4}  "
          f"accepted-invalid {c['accepted']:>4}" + (f"  limit {c['limit']}" if c["limit"] else ""))
for f, k, a, why in limited:
    print(f"limit: {f} record {k + 1} answer {a + 1}: {why}")
if args.compliance:
    with open(args.compliance, "w", encoding="utf-8") as f:
        json.dump({"right": total["right"], "wrong": total["wrong"], "refused": total["refused"],
                   "acceptedInvalid": total["accepted"], "limited": total["limit"],
                   "judge": {"runner": "https://github.com/corvus-dotnet/corvus-regex-ecma262-java/blob/main/tools/benchmarks/tregex-compliance.py",
                             "calls": {"utf16": ["RegExp.prototype.test", "RegExp.prototype.exec", "String.prototype.matchAll",
                                                 "String.prototype.replace", "String.prototype.split"]}},
                   "excludes": [{"class": "encoding", "leaves": "utf8"}]}, f)
        f.write("\n")
print("")
print(f"records {total['records']} (skipped {total['skipped']} deviations)  right {total['right']}  wrong {total['wrong']}  "
      f"refused-valid {total['refused']}  accepted-invalid {total['accepted']}  limit {total['limit']}  errors 0  "
      f"in {time.time() - start:.1f}s  {engine}".rstrip())
