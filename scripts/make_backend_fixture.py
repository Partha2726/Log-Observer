"""Builds the backend's Elasticsearch fixture from a real generated run (standard library only).

Input: the output of scripts/test-filebeat.sh run with the production filters (each event carries
test_doc_id), the generated log files and the ground truth. Output: one JSON line per document,
{"_id", "_index", "source", "truth"}, for a handful of flows (every scenario present plus a few NORMAL
ones) and every other document within 2 s on the same host of their lines (other flows, noise), so
assembly tests see realistic thread and time windows. Generated data only.

usage: make_backend_fixture.py <work dir of test-filebeat.sh> <out dir> [normal flows]
"""
import bisect
import collections
import datetime
import json
import os
import sys


def line_starts(path):
    starts = [0]
    with open(path, "rb") as f:
        data = f.read()
    pos = data.find(b"\n")
    while pos != -1:
        starts.append(pos + 1)
        pos = data.find(b"\n", pos + 1)
    return starts


def parse_ts(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def main(work, out_dir, normal_count="4"):
    logs = os.path.join(work, "logs")
    truth_dir = os.path.join(work, "truth")
    flows = [json.loads(l) for l in open(os.path.join(truth_dir, "flows.jsonl"), encoding="utf-8")]
    chosen, normals = [], 0
    seen = set()
    for f in flows:
        if f["scenario"] != "NORMAL" and f["scenario"] not in seen:
            chosen.append(f); seen.add(f["scenario"])
        elif f["scenario"] == "NORMAL" and normals < int(normal_count):
            chosen.append(f); normals += 1
    refs = {f["flowRef"] for f in chosen}

    truth = {}
    event_lines = collections.defaultdict(list)
    for line in open(os.path.join(truth_dir, "lines.jsonl"), encoding="utf-8"):
        row = json.loads(line)
        truth[(row["file"], row["lineNo"])] = row
        event_lines[row["file"]].append(row["lineNo"])
    for v in event_lines.values():
        v.sort()

    starts_cache = {}
    docs = []
    for line in open(os.path.join(work, "out", "actual.jsonl"), encoding="utf-8"):
        event = json.loads(line)
        rel = event["log"]["file"]["path"].removeprefix("/logs/")
        if rel not in starts_cache:
            starts_cache[rel] = line_starts(os.path.join(logs, rel))
        # Filebeat reports a multi-line event at the offset of its last line; the event starts at the
        # nearest ground-truth event line at or before it.
        line_no = bisect.bisect_right(starts_cache[rel], event["log"]["offset"])
        first = event_lines[rel][bisect.bisect_right(event_lines[rel], line_no) - 1]
        docs.append((event, truth[(rel, first)]))

    flow_docs = [(e, t) for e, t in docs if t["flowRef"] in refs]
    windows = collections.defaultdict(list)
    for e, _ in flow_docs:
        ts = parse_ts(e["@timestamp"])
        windows[(e.get("host") or {}).get("name")].append(ts)
    for v in windows.values():
        v.sort()

    selected = []
    for e, t in docs:
        keep = t["flowRef"] in refs
        if not keep:
            times = windows.get((e.get("host") or {}).get("name"), [])
            ts = parse_ts(e["@timestamp"])
            i = bisect.bisect_left(times, ts - datetime.timedelta(seconds=2))
            keep = i < len(times) and times[i] <= ts + datetime.timedelta(seconds=2)
        if keep:
            doc_id = e.pop("test_doc_id")
            for transient in ("@version", "ingested_at"):
                e.pop(transient, None)
            e["ingested_at"] = "2026-05-16T00:00:00.000Z"
            index = "logs-flow-" + e["@timestamp"][:10].replace("-", ".")
            selected.append({"_id": doc_id, "_index": index, "source": e,
                             "truth": {k: t[k] for k in ("flowRef", "requestId", "stage", "isNoise")}})
    selected.sort(key=lambda d: (d["source"]["@timestamp"], d["source"]["log"]["file"]["path"], d["source"]["log"]["offset"]))

    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "demo-docs.jsonl"), "w", encoding="utf-8") as f:
        for d in selected:
            f.write(json.dumps(d, sort_keys=True) + "\n")
    with open(os.path.join(out_dir, "demo-flows.jsonl"), "w", encoding="utf-8") as f:
        for flow in chosen:
            f.write(json.dumps(flow, sort_keys=True) + "\n")
    print(f"{len(selected)} documents for {len(chosen)} flows: {sorted((f['scenario'], f['flowRef']) for f in chosen)}")


if __name__ == "__main__":
    main(*sys.argv[1:])
