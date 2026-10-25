"""Log Observer bench (sections 13 and 17; PERF-001…005). Python 3 standard library only.

Measures the running stack after `make demo` (default dataset) and writes evaluation/bench.md:
  PERF-001 stored trace GET          p95 <= 300 ms   (GET /api/traces/{flowRef}, already assembled)
  PERF-002 on-demand assembly        p95 <= 2 s      (GET /api/traces/{flowRef}?rebuild=true, rules included)
  PERF-003 flow search               p95 <= 1 s      (GET /api/search/flows?userId=…, 7 days)
  PERF-004 ingestion of the dataset  <= 15 min       (span of ingested_at, measured by scripts/bench.sh)
  log search                         reported        (GET /api/search/logs?q=…, 7 days)
  PERF-005 memory                    <= 8 GB         (peak sum of `docker stats` over the stack's containers during the run)
Each latency is 200 sequential requests. Only these measured numbers may be quoted in docs.

usage: python3 evaluation/bench.py [--base URL] [--requests N] [--ingest-seconds S] [--ingested-docs N] [--out FILE]
Exit code 0 when every target is met, 1 otherwise.
"""
import argparse
import datetime
import json
import os
import random
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

TARGETS_MS = {"stored": 300, "assembly": 2000, "flow_search": 1000}
INGEST_TARGET_S = 15 * 60
MEMORY_TARGET_BYTES = 8 * 1024 ** 3
UNITS = {"B": 1, "KiB": 1024, "MiB": 1024 ** 2, "GiB": 1024 ** 3, "kB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3}


class Api:
    def __init__(self, base, user, password):
        self.base = base.rstrip("/")
        body = json.dumps({"username": user, "password": password}).encode()
        req = urllib.request.Request(f"{self.base}/api/auth/login", data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as r:
            self.token = json.load(r)["accessToken"]

    def get(self, path):
        req = urllib.request.Request(f"{self.base}{path}", headers={"Authorization": f"Bearer {self.token}"})
        started = time.perf_counter()
        with urllib.request.urlopen(req, timeout=120) as r:
            r.read()
        return (time.perf_counter() - started) * 1000


def percentile(values, p):
    s = sorted(values)
    k = max(0, min(len(s) - 1, int(round(p / 100 * len(s) + 0.5)) - 1))
    return s[k]


def mem_bytes(text):
    num = "".join(ch for ch in text if ch.isdigit() or ch == ".")
    unit = text[len(num):].strip()
    return float(num) * UNITS.get(unit, 1)


class DockerSampler(threading.Thread):
    """Samples `docker stats` every 2 s and keeps the peak total of the stack's containers (Kibana excluded)."""

    def __init__(self, prefix="log-observer"):
        super().__init__(daemon=True)
        self.prefix = prefix
        self.peak = 0.0
        self.peak_by = {}
        self.stop = threading.Event()

    def run(self):
        while not self.stop.is_set():
            try:
                out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.MemUsage}}"],
                                     capture_output=True, text=True, timeout=30).stdout
                by = {}
                for line in out.splitlines():
                    name, usage = line.split("\t")
                    if name.startswith(self.prefix) and "kibana" not in name:
                        by[name] = mem_bytes(usage.split("/")[0].strip())
                total = sum(by.values())
                if total > self.peak:
                    self.peak, self.peak_by = total, by
            except (OSError, ValueError, subprocess.SubprocessError):
                pass
            self.stop.wait(2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.environ.get("EVAL_BASE_URL", "http://localhost:8080"))
    ap.add_argument("--user", default="devops.dev")
    ap.add_argument("--password", default="DevPassword123!")
    ap.add_argument("--truth", default="data/ground-truth")
    ap.add_argument("--requests", type=int, default=200)
    ap.add_argument("--ingest-seconds", type=float, default=None)
    ap.add_argument("--ingested-docs", type=int, default=None)
    ap.add_argument("--out", default="evaluation/bench.md")
    args = ap.parse_args()

    flows = [json.loads(l) for l in open(os.path.join(args.truth, "flows.jsonl"), encoding="utf-8")]
    rng = random.Random(42)
    refs = [f["flowRef"] for f in rng.sample(flows, min(args.requests, len(flows)))]
    users = [f["requester"] for f in rng.sample(flows, min(args.requests, len(flows)))]
    starts = sorted(f["startedAt"] for f in flows)
    to = datetime.datetime.fromisoformat(starts[-1].replace("Z", "+00:00")) + datetime.timedelta(days=1)
    frm = to - datetime.timedelta(days=7)
    window = f"from={urllib.parse.quote(frm.isoformat())}&to={urllib.parse.quote(to.isoformat())}"
    words = ["timeout", "failed", "approved", "Provider", "Budget", "validation", "SQLTimeoutException", "created"]

    sampler = DockerSampler()
    sampler.start()
    api = Api(args.base, args.user, args.password)
    results = {}
    started = datetime.datetime.now(datetime.timezone.utc)
    for ref in refs[:5]:
        api.get(f"/api/traces/{ref}")  # make sure they are stored before timing stored reads
    results["stored"] = [api.get(f"/api/traces/{ref}") for ref in refs]
    results["assembly"] = [api.get(f"/api/traces/{ref}?rebuild=true") for ref in refs]
    results["flow_search"] = [api.get(f"/api/search/flows?userId={u}&{window}") for u in users]
    results["log_search"] = [api.get(f"/api/search/logs?q={rng.choice(words)}&{window}") for _ in range(args.requests)]
    sampler.stop.set()
    sampler.join(timeout=35)
    finished = datetime.datetime.now(datetime.timezone.utc)

    met = {k: percentile(results[k], 95) <= v for k, v in TARGETS_MS.items()}
    if args.ingest_seconds is not None:
        met["ingest"] = args.ingest_seconds <= INGEST_TARGET_S
    if sampler.peak > 0:
        met["memory"] = sampler.peak <= MEMORY_TARGET_BYTES

    labels = {"stored": "PERF-001 Stored trace GET", "assembly": "PERF-002 On-demand assembly (rebuild, rules included)",
              "flow_search": "PERF-003 Flow search", "log_search": "Log search (free text, 7 days)"}
    out = ["# Bench report\n",
           f"Generated by `make bench` (`evaluation/bench.py`) on {finished:%Y-%m-%d %H:%M} UTC in {(finished - started).seconds} s against "
           f"{args.base}, on the default dataset ({len(flows):,} flows). {args.requests} sequential requests per measurement; "
           "only these measured numbers may be quoted.\n",
           "| Measurement | p50 | p95 | Target (p95) | Met |", "| --- | --- | --- | --- | --- |"]
    for k, label in labels.items():
        target = TARGETS_MS.get(k)
        out.append(f"| {label} | {percentile(results[k], 50):.0f} ms | {percentile(results[k], 95):.0f} ms | "
                   f"{f'≤ {target} ms' if target else 'reported'} | {('yes' if met[k] else '**no**') if target else '—'} |")
    if args.ingest_seconds is not None:
        out.append(f"| PERF-004 Ingestion of the dataset ({args.ingested_docs or 0:,} documents) | — | {args.ingest_seconds / 60:.1f} min | "
                   f"≤ 15 min | {'yes' if met['ingest'] else '**no**'} |")
    if sampler.peak > 0:
        out.append(f"| PERF-005 Peak memory of the stack (docker stats, no Kibana) | — | {sampler.peak / 1024 ** 3:.2f} GiB | ≤ 8 GB | "
                   f"{'yes' if met['memory'] else '**no**'} |")
        out.append("\nPeak memory by container: " + ", ".join(f"{n} {b / 1024 ** 2:.0f} MiB" for n, b in sorted(sampler.peak_by.items())) + ".")
    out.append("\nThe machine is the developer laptop running the stack in Docker Desktop; numbers vary with hardware and load.")
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print("\n".join(out))
    return 0 if all(met.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
