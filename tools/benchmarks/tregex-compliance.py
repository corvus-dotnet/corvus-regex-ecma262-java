#!/usr/bin/env python3
"""TRegex's compliance over the ECMA-262 test data: tools/benchmarks/v8/dataset.mjs run by GraalVM's Node, whose RegExp
is TRegex, with a limit of time on each record.

TRegex does not answer some records of the data in any time a run can wait (25 records of backreferences.jsonl and
captures.jsonl with GraalJS 25.4.4.1.1, each named in the output; the first found:
backreferences.jsonl, record 909, /^(?:(?<n0>(a|-){0,0})|(?<n0>(\\k<n0>*\\4?|\\2{2}){3,3})){3,5}\\k<n0>a/u on "xx",
which had run for minutes when it was stopped; V8 and this library answer it at once). A JavaScript engine cannot be
stopped inside a match, so the script runs in a process of its own that writes each record's counts as it judges it;
when a record gives none within the limit, the process is stopped, the record is counted as not asked (every answer it
has goes into "limit", as the judging rule of the data counts an answer an engine never gave), and a new process goes
on from the next record. The output is the runner's: a line for each file and the last line of counts, with each
record that reached the limit named.

    tregex-compliance.py <GraalVM's node> <data directory> [--limit seconds (30)]

run.sh runs it with TREGEX_COMPLIANCE=1; otherwise it takes tools/benchmarks/compliance-tregex.txt, this script's
output, since a run takes about twenty minutes (GraalJS compiles each pattern, and each record at the limit waits
thirty seconds), where V8's takes fifteen seconds.
"""
import argparse, os, select, signal, subprocess, sys, time

ap = argparse.ArgumentParser()
ap.add_argument("node")
ap.add_argument("data")
ap.add_argument("--limit", type=float, default=30.0)
args = ap.parse_args()
script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "v8", "dataset.mjs")

# Every record in the order the script judges them: (file, index).
order = []
for name in sorted(f for f in os.listdir(args.data) if f.endswith(".jsonl")):
    with open(os.path.join(args.data, name), encoding="utf-8") as f:
        n = sum(1 for line in f if line.strip())
    order.extend((name, k) for k in range(n))
position = {r: k for k, r in enumerate(order)}

FIELDS = ["right", "wrong", "refused", "accepted", "limit", "records", "skipped"]
per_file = {}
limited = []
done = -1  # the position of the last record judged
start = time.time()
engine = ""
while done + 1 < len(order):
    env = dict(os.environ, DATASET_PROGRESS="1", DATASET_LIMITED="\n".join(f"{f}\t{k}" for f, k in limited))
    nxt = order[done + 1]
    env["DATASET_FROM"] = f"{nxt[0]}\t{nxt[1]}"
    # In a process group of its own: dataset.mjs judges each file in a child process, so stopping the script alone
    # would leave the child that is stuck in the match running, and every record at the limit would add one.
    child = subprocess.Popen([args.node, script, args.data], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             text=True, encoding="utf-8", start_new_session=True)
    buf = ""
    last = time.time()
    finished = False
    while True:
        ready, _, _ = select.select([child.stderr], [], [], 1.0)
        if ready:
            chunk = os.read(child.stderr.fileno(), 65536).decode("utf-8", "replace")
            if not chunk:
                finished = True
                break
            buf += chunk
            *lines, buf = buf.split("\n")
            for line in lines:
                if not line.startswith("P\t"):
                    continue
                _, name, index, *counts = line.split("\t")
                c = per_file.setdefault(name, dict.fromkeys(FIELDS, 0))
                for key, value in zip(FIELDS, counts):
                    c[key] += int(value)
                done = position[(name, int(index))]
                last = time.time()
        elif time.time() - last > args.limit:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait()
            hung = order[done + 1]
            limited.append(hung)
            print(f"LIMIT {hung[0]} record {hung[1]}: no answer in {args.limit:.0f} s", file=sys.stderr, flush=True)
            break
    if finished:
        out = child.stdout.read()
        child.wait()
        for line in out.splitlines():
            if line.startswith("records ") and "(" in line:
                engine = line[line.rindex("("):]
        if done + 1 < len(order):
            raise SystemExit(f"the script ended at {order[done + 1]} without judging it: {out[-500:]}")

total = dict.fromkeys(FIELDS, 0)
for name in sorted(per_file):
    c = per_file[name]
    for key in FIELDS:
        total[key] += c[key]
    print(f"{name:<24} right {c['right']:>10}  wrong {c['wrong']:>6}  refused-valid {c['refused']:>4}  "
          f"accepted-invalid {c['accepted']:>4}" + (f"  limit {c['limit']}" if c["limit"] else ""))
for f, k in limited:
    print(f"limit: {f} record {k}")
print("")
print(f"records {total['records']} (skipped {total['skipped']} deviations)  right {total['right']}  wrong {total['wrong']}  "
      f"refused-valid {total['refused']}  accepted-invalid {total['accepted']}  limit {total['limit']}  errors 0  "
      f"in {time.time() - start:.1f}s  {engine}".rstrip())
