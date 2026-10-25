"""Builds the backend's rules fixture (RULE-020) from a generated 1,000-flow run (standard library only).

Input: the output of scripts/test-filebeat.sh run with the production filters and GEN_START set to a day
other than the demo fixture's (so flowRefs never collide). Output, gzip-compressed JSON lines:
  rules-docs.jsonl.gz   {"_id", "_index", "source", "truth"} for the chosen flows and incidents
  rules-flows.jsonl     flows.jsonl rows of the chosen flows
  rules-incidents.jsonl incidents.jsonl rows of the chosen incidents
  rules-users.jsonl     users.jsonl (approval limits for R10)
Chosen: two flows of every flow scenario (both flows of one DUPLICATE_SUBMIT pair), twelve NORMAL flows,
and two incidents of every incident scenario with the lines that make them (the user's or IP's lines in
the incident window; for concurrent sessions, also the user's sign-in and sign-out lines around it).
Generated data only.

usage: make_rules_fixture.py <work dir of test-filebeat.sh> <out dir>
"""
import bisect
import collections
import datetime
import gzip
import json
import os
import sys

PER_SCENARIO = 2
NORMALS = 12
SESSION_ACTIONS = {"LOGIN_OK", "LOGOUT", "SESSION_EXPIRED"}


def line_starts(path):
    starts = [0]
    with open(path, "rb") as f:
        data = f.read()
    pos = data.find(b"\n")
    while pos != -1:
        starts.append(pos + 1)
        pos = data.find(b"\n", pos + 1)
    return starts


def ts(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def get(event, dotted):
    node = event
    for part in dotted.split("."):
        node = node.get(part) if isinstance(node, dict) else None
    return node


def main(work, out_dir):
    truth_dir = os.path.join(work, "truth")
    flows = [json.loads(l) for l in open(os.path.join(truth_dir, "flows.jsonl"), encoding="utf-8")]
    incidents = [json.loads(l) for l in open(os.path.join(truth_dir, "incidents.jsonl"), encoding="utf-8")]

    by_scenario = collections.defaultdict(list)
    for f in flows:
        by_scenario[f["scenario"]].append(f)
    chosen = by_scenario["NORMAL"][:NORMALS]
    for scenario, rows in sorted(by_scenario.items()):
        if scenario == "NORMAL":
            continue
        if scenario == "DUPLICATE_SUBMIT":
            pairs = collections.defaultdict(list)
            for f in rows:
                pairs[(f["requester"], f["amount"])].append(f)
            chosen += next(p for p in pairs.values() if len(p) == 2)
        else:
            chosen += rows[:PER_SCENARIO]
    refs = {f["flowRef"] for f in chosen}

    picked = []
    per_incident = collections.Counter()
    for i in incidents:
        if per_incident[i["scenario"]] < PER_SCENARIO:
            picked.append(i)
            per_incident[i["scenario"]] += 1

    truth = {}
    event_lines = collections.defaultdict(list)
    for line in open(os.path.join(truth_dir, "lines.jsonl"), encoding="utf-8"):
        row = json.loads(line)
        truth[(row["file"], row["lineNo"])] = row
        event_lines[row["file"]].append(row["lineNo"])
    for v in event_lines.values():
        v.sort()

    def in_incident(event):
        t = ts(event["@timestamp"])
        user, ip, action = get(event, "user.id"), get(event, "client.ip"), get(event, "event.action")
        for i in picked:
            start, end = ts(i["windowStart"]), ts(i["windowEnd"])
            if i["scenario"] == "CONCURRENT_SESSIONS" and user == i["userId"] and action in SESSION_ACTIONS \
                    and start - datetime.timedelta(hours=4) <= t <= end + datetime.timedelta(hours=4):
                return True
            if start - datetime.timedelta(seconds=1) <= t <= end + datetime.timedelta(seconds=15) \
                    and ((i["userId"] and user == i["userId"]) or (i["clientIp"] and ip == i["clientIp"])):
                return True
        return False

    starts_cache = {}
    selected = []
    for line in open(os.path.join(work, "out", "actual.jsonl"), encoding="utf-8"):
        event = json.loads(line)
        rel = event["log"]["file"]["path"].removeprefix("/logs/")
        if rel not in starts_cache:
            starts_cache[rel] = line_starts(os.path.join(work, "logs", rel))
        line_no = bisect.bisect_right(starts_cache[rel], event["log"]["offset"])
        first = event_lines[rel][bisect.bisect_right(event_lines[rel], line_no) - 1]
        t = truth[(rel, first)]
        if t["flowRef"] in refs or in_incident(event):
            doc_id = event.pop("test_doc_id")
            for transient in ("@version", "ingested_at"):
                event.pop(transient, None)
            event["ingested_at"] = "2026-06-12T00:00:00.000Z"
            selected.append({"_id": doc_id, "_index": "logs-flow-" + event["@timestamp"][:10].replace("-", "."), "source": event,
                             "truth": {k: t[k] for k in ("flowRef", "requestId", "stage", "isNoise")}})
    selected.sort(key=lambda d: (d["source"]["@timestamp"], d["source"]["log"]["file"]["path"], d["source"]["log"]["offset"]))

    os.makedirs(out_dir, exist_ok=True)
    with gzip.open(os.path.join(out_dir, "rules-docs.jsonl.gz"), "wt", encoding="utf-8", compresslevel=9) as f:
        for d in selected:
            f.write(json.dumps(d, sort_keys=True) + "\n")
    for name, rows in (("rules-flows.jsonl", chosen), ("rules-incidents.jsonl", picked)):
        with open(os.path.join(out_dir, name), "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, sort_keys=True) + "\n")
    with open(os.path.join(truth_dir, "users.jsonl"), encoding="utf-8") as src, \
            open(os.path.join(out_dir, "rules-users.jsonl"), "w", encoding="utf-8") as dst:
        dst.write(src.read())
    print(f"{len(selected)} documents for {len(chosen)} flows and {len(picked)} incidents")


if __name__ == "__main__":
    main(*sys.argv[1:])
