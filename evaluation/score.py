"""Log Observer evaluation (spec section 13, EVAL-001). Python 3 standard library only.

Scores the running backend against the generator's ground truth:
  * line attribution: precision of OBSERVED and CORRELATED links, recall of each flow's lines;
  * stage status: exact match of every stage of a flow (flows.jsonl);
  * first-failure stage (and component) for failed flows;
  * rule detection: recall of each scenario's expected rule (section 5.5) and the false-positive rate on
    NORMAL flows. Trace-scoped rules are read from each trace; DUPLICATE_SUBMIT pairs and the incidents of
    incidents.jsonl (brute force, concurrent sessions, probing, scanner) through GET /api/anomalies.

Every trace is opened through the REST API (GET /api/traces/{flowRef}, which assembles on demand)
and its evidence is read through GET /api/traces/{flowRef}/events. Evidence document IDs are mapped
to ground-truth lines by recomputing the pipeline's document ID, SHA-256 of
"|[log][file][path]|<path>|[log][offset]|<offset>|", where Filebeat reports a multi-line event at the
offset of its last line.

usage: python3 evaluation/score.py [--base URL] [--truth DIR] [--logs DIR] [--sample N] [--threads N] [--out FILE]
Exit code 0 when every target is met, 1 otherwise. --no-rules skips rule detection (phase 1 scope).
"""
import argparse
import collections
import concurrent.futures
import datetime
import hashlib
import json
import os
import random
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request

TARGETS = {
    "observed_precision": 0.99,
    "correlated_precision": 0.85,
    "stage_status_accuracy": 0.98,
    "first_failure_stage_accuracy": 0.95,
}
RULE_RECALL_TARGET = 0.90
NORMAL_FP_TARGET = 0.02

# Section 5.5: the rules each scenario must fire. Incident scenarios are scored from incidents.jsonl.
EXPECTED_RULES = {
    "FAIL_DB_TIMEOUT_APPROVE": {"R05", "R06"},
    "FAIL_DB_DOWN_CREATE": {"R05"},
    "FAIL_VALIDATION_CREATE": {"R05"},
    "FAIL_PROVIDER_TIMEOUT_EXECUTE": {"R05", "R13"},
    "SELF_APPROVAL": {"R01"},
    "EXECUTE_WITHOUT_APPROVAL": {"R03"},
    "OUT_OF_ORDER": {"R04"},
    "SLOW_APPROVE": {"R09"},
    "LIMIT_BREACH": {"R10"},
    "DUPLICATE_SUBMIT": {"R11"},
    "OFF_HOURS": {"R12"},
}
INCIDENT_SCENARIOS = {"BRUTE_FORCE_LOGIN", "CONCURRENT_SESSIONS", "AUTHZ_PROBING", "SCANNER_TRAFFIC"}
# A NORMAL trace may fire these at their natural base rate (section 10.3); they still count as false positives.
BASE_RATE_RULES = {"R09", "R12"}


class Api:
    def __init__(self, base, user, password):
        self.base = base.rstrip("/")
        self.user = user
        self.password = password
        self.lock = threading.Lock()
        self.token = None
        self.login()

    def login(self):
        body = json.dumps({"username": self.user, "password": self.password}).encode()
        req = urllib.request.Request(f"{self.base}/api/auth/login", data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as r:
            with self.lock:
                self.token = json.load(r)["accessToken"]

    def get(self, path, retry=True):
        req = urllib.request.Request(f"{self.base}{path}", headers={"Authorization": f"Bearer {self.token}"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 401 and retry:
                self.login()
                return self.get(path, retry=False)
            raise


def line_starts(path):
    starts = [0]
    with open(path, "rb") as f:
        data = f.read()
    pos = data.find(b"\n")
    while pos != -1:
        if pos + 1 < len(data):
            starts.append(pos + 1)
        pos = data.find(b"\n", pos + 1)
    return starts


def doc_index(truth_dir, logs_dir):
    """Document ID -> (flowRef, stage), and per-flow ground-truth line counts."""
    rows_by_file = collections.defaultdict(list)
    with open(os.path.join(truth_dir, "lines.jsonl"), encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            rows_by_file[r["file"]].append((r["lineNo"], r["flowRef"], r["stage"]))
    index = {}
    lines_per_flow = collections.Counter()
    for rel, rows in rows_by_file.items():
        rows.sort()
        starts = line_starts(os.path.join(logs_dir, rel))
        for i, (line_no, flow_ref, stage) in enumerate(rows):
            last_line = (rows[i + 1][0] - 1) if i + 1 < len(rows) else len(starts)
            offset = starts[last_line - 1]
            key = f"|[log][file][path]|/logs/{rel}|[log][offset]|{offset}|"
            service = rel.split("/", 1)[0]
            index[hashlib.sha256(key.encode()).hexdigest()] = (flow_ref, stage, service)
            if flow_ref:
                lines_per_flow[flow_ref] += 1
                lines_per_flow[(flow_ref, service)] += 1
    return index, lines_per_flow


def score_flow(api, flow, index):
    ref = flow["flowRef"]
    trace = api.get(f"/api/traces/{ref}")
    links = []
    page = 0
    while True:
        p = api.get(f"/api/traces/{ref}/events?size=100&page={page}")
        links.extend(p["items"])
        page += 1
        if page >= p["totalPages"]:
            break
    return trace, links


def pct(n, d):
    return n / d if d else 1.0


def iso(dt):
    return dt.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def anomalies(api, **query):
    """Every anomaly matching the query, through GET /api/anomalies (all pages)."""
    out, page = [], 0
    while True:
        q = "&".join(f"{k}={urllib.parse.quote(str(v))}" for k, v in query.items() if v is not None)
        p = api.get(f"/api/anomalies?{q}&size=100&page={page}")
        out.extend(p["items"])
        page += 1
        if page >= p["totalPages"]:
            return out


def score_rules(api, flows, traces, incidents):
    """Recall per scenario and false positives on NORMAL; returns (rows, normal_fp, normal_fp_strict, misses)."""
    per = collections.defaultdict(collections.Counter)
    misses = []
    # R11 rows sit on the later flow of a pair and name both flowRefs.
    times = [datetime.datetime.fromisoformat(f["startedAt"].replace("Z", "+00:00")) for f in flows]
    dup_refs = set()
    if any(f["scenario"] == "DUPLICATE_SUBMIT" for f in flows):
        for a in anomalies(api, ruleId="R11", **{"from": iso(min(times) - datetime.timedelta(days=1)),
                                                 "to": iso(max(times) + datetime.timedelta(days=1))}):
            dup_refs.update({a["details"].get("earlierFlowRef"), a["details"].get("laterFlowRef")})
    normal_fp = normal_fp_strict = normals = 0
    for f in flows:
        trace = traces.get(f["flowRef"])
        if trace is None:
            continue
        fired = {a["ruleId"] for a in trace["anomalies"]}
        scenario = f["scenario"]
        if scenario == "NORMAL":
            normals += 1
            normal_fp += bool(fired)
            normal_fp_strict += bool(fired - BASE_RATE_RULES)
            per["NORMAL"]["fired:" + ",".join(sorted(fired)) if fired else "clean"] += 1
            continue
        expected = EXPECTED_RULES.get(scenario)
        if not expected:
            continue
        hit = f["flowRef"] in dup_refs if scenario == "DUPLICATE_SUBMIT" else expected <= fired
        per[scenario]["total"] += 1
        per[scenario]["hit"] += hit
        if not hit:
            misses.append(f"{f['flowRef']} ({scenario}) expected {sorted(expected)}, fired {sorted(fired)}")
    for i in incidents:
        rule = i["expectedRule"]
        start = datetime.datetime.fromisoformat(i["windowStart"].replace("Z", "+00:00")) - datetime.timedelta(minutes=1)
        end = datetime.datetime.fromisoformat(i["windowEnd"].replace("Z", "+00:00")) + datetime.timedelta(minutes=1)
        subject = {"clientIp": i["clientIp"]} if rule == "R15" else {"userId": i["userId"]}
        found = anomalies(api, ruleId=rule, **subject, **{"from": iso(start), "to": iso(end)})
        per[i["scenario"]]["total"] += 1
        per[i["scenario"]]["hit"] += bool(found)
        if not found:
            misses.append(f"incident {i['scenario']} {subject} {i['windowStart']} expected {rule}")
    return per, (normal_fp, normal_fp_strict, normals), misses


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.environ.get("EVAL_BASE_URL", "http://localhost:8080"))
    ap.add_argument("--user", default="devops.dev")
    ap.add_argument("--password", default="DevPassword123!")
    ap.add_argument("--truth", default="data/ground-truth")
    ap.add_argument("--logs", default="data/logs")
    ap.add_argument("--sample", type=int, default=0, help="score a random sample of N flows (0 = all)")
    ap.add_argument("--threads", type=int, default=6)
    ap.add_argument("--out", default="evaluation/report.md")
    ap.add_argument("--no-rules", action="store_true", help="skip rule detection (phase 1 scope)")
    ap.add_argument("--json-out", default=None, help="also write the measured numbers as JSON (for evaluation/compare_otel.py)")
    args = ap.parse_args()

    started = datetime.datetime.now(datetime.timezone.utc)
    flows = [json.loads(l) for l in open(os.path.join(args.truth, "flows.jsonl"), encoding="utf-8")]
    if args.sample and args.sample < len(flows):
        flows = random.Random(42).sample(flows, args.sample)
    index, lines_per_flow = doc_index(args.truth, args.logs)
    api = Api(args.base, args.user, args.password)

    obs = collections.Counter()
    cor = collections.Counter()
    recall = collections.Counter()
    stage_right = collections.Counter()
    per_scenario = collections.defaultdict(collections.Counter)
    misses = []
    unmapped = 0
    errors = []
    traces = {}
    by_service = collections.defaultdict(collections.Counter)
    by_reason = collections.Counter()

    def work(flow):
        try:
            return flow, score_flow(api, flow, index), None
        except Exception as e:  # noqa: BLE001 - report every failed flow, keep scoring the rest
            return flow, None, e

    done = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as pool:
        for flow, result, error in pool.map(work, flows):
            done += 1
            if done % 500 == 0:
                print(f"  scored {done}/{len(flows)} flows", file=sys.stderr)
            scenario = flow["scenario"]
            if error is not None:
                errors.append(f"{flow['flowRef']}: {error}")
                per_scenario[scenario]["errors"] += 1
                continue
            trace, links = result
            ref = flow["flowRef"]
            traces[ref] = trace
            linked_truth = 0
            for link in links:
                truth = index.get(link["docId"])
                if truth is None:
                    unmapped += 1
                    continue
                right = truth[0] == ref
                svc = by_service[truth[2]]
                svc["linked"] += 1
                svc["observed" if link["evidenceClass"] == "OBSERVED" else "correlated"] += 1
                svc["correct"] += right
                svc["observed_correct" if link["evidenceClass"] == "OBSERVED" else "correlated_correct"] += right
                by_reason[link["linkReason"]] += 1
                bucket = obs if link["evidenceClass"] == "OBSERVED" else cor
                bucket["total"] += 1
                bucket["correct"] += right
                if right:
                    linked_truth += 1
                    bucket["stage_correct"] += link["stage"] == truth[1]
            recall["truth"] += lines_per_flow[ref]
            recall["linked"] += linked_truth
            for service in ("gateway", "auth-service", "workflow-service", "billing-service"):
                by_service[service]["truth"] += lines_per_flow[(ref, service)]

            got = {s["stage"]: s["status"] for s in trace["stages"]}
            stages_ok = all(got.get(stage) == status for stage, status in flow["stages"].items())
            per_scenario[scenario]["flows"] += 1
            per_scenario[scenario]["status_ok"] += stages_ok
            per_scenario[scenario]["overall_ok"] += trace["overallStatus"] == flow["overallStatus"]
            stage_right["flows"] += 1
            stage_right["ok"] += stages_ok
            for stage, status in flow["stages"].items():
                stage_right["stages"] += 1
                stage_right["stage_ok"] += got.get(stage) == status

            expected = flow["expectedFailingStage"]
            ff = trace["firstFailure"]
            ff_ok = None
            if expected:
                ff_ok = ff is not None and ff["stage"] == expected
                comp_ok = ff is not None and ff["component"] == flow["expectedFailingComponent"]
                stage_right["failed"] += 1
                stage_right["ff_ok"] += ff_ok
                stage_right["ff_component_ok"] += comp_ok
                per_scenario[scenario]["ff_ok"] += ff_ok
                per_scenario[scenario]["failed"] += 1
            if not stages_ok or ff_ok is False:
                misses.append({
                    "flowRef": ref, "scenario": scenario,
                    "stages": {k: (flow["stages"][k], got.get(k)) for k in flow["stages"] if flow["stages"][k] != got.get(k)},
                    "firstFailure": (expected, flow["expectedFailingComponent"],
                                     ff["stage"] if ff else None, ff["component"] if ff else None) if expected else None,
                })

    metrics = {
        "observed_precision": pct(obs["correct"], obs["total"]),
        "correlated_precision": pct(cor["correct"], cor["total"]),
        "stage_status_accuracy": pct(stage_right["ok"], stage_right["flows"]),
        "first_failure_stage_accuracy": pct(stage_right["ff_ok"], stage_right["failed"]),
    }
    met = {k: metrics[k] >= v for k, v in TARGETS.items()}
    rule_rows, rule_misses = {}, []
    if not args.no_rules:
        incidents_path = os.path.join(args.truth, "incidents.jsonl")
        incidents = [json.loads(l) for l in open(incidents_path, encoding="utf-8")] if os.path.exists(incidents_path) else []
        if args.sample:
            incidents = random.Random(42).sample(incidents, min(len(incidents), max(1, args.sample // 50)))
        rule_rows, (fp, fp_strict, normals), rule_misses = score_rules(api, flows, traces, incidents)
        for scenario, c in rule_rows.items():
            if scenario != "NORMAL":
                met[f"rule_recall:{scenario}"] = pct(c["hit"], c["total"]) >= RULE_RECALL_TARGET
        met["normal_false_positives"] = pct(fp, normals) <= NORMAL_FP_TARGET if normals else True
    finished = datetime.datetime.now(datetime.timezone.utc)
    total_events = sum(1 for _ in open(os.path.join(args.truth, "lines.jsonl"), encoding="utf-8"))

    out = []
    out.append("# Evaluation report\n")
    out.append(f"Generated by `evaluation/score.py` on {finished:%Y-%m-%d %H:%M} UTC in {(finished - started).seconds} s "
               f"against {args.base}: line attribution, stage status, first failure"
               + (" and rule detection.\n" if not args.no_rules else ". Rule detection was skipped (--no-rules).\n"))
    out.append("## Dataset\n")
    out.append(f"- Flows scored: {stage_right['flows']} of {len(flows)} sampled"
               f"{' (random sample, seed 42)' if args.sample else ' (all flows)'}; ground-truth events: {total_events:,}")
    out.append(f"- Evidence lines read through the API: {obs['total'] + cor['total'] + unmapped:,} "
               f"(OBSERVED {obs['total']:,}, CORRELATED {cor['total']:,}, unmapped to ground truth {unmapped})")
    out.append(f"- Errors while scoring: {len(errors)}\n")
    out.append("## Targets (section 13)\n")
    out.append("| Metric | Value | Target | Met |\n| --- | --- | --- | --- |")
    labels = {
        "observed_precision": "OBSERVED lines correct",
        "correlated_precision": "CORRELATED precision",
        "stage_status_accuracy": "Stage status (all stages of a flow exact)",
        "first_failure_stage_accuracy": "First-failure stage",
    }
    for k, v in TARGETS.items():
        out.append(f"| {labels[k]} | {metrics[k]:.2%} | ≥ {v:.0%} | {'yes' if met[k] else '**no**'} |")
    out.append("\n## More measurements\n")
    out.append("| Measurement | Value |\n| --- | --- |")
    out.append(f"| Line recall (ground-truth lines of each flow that were linked) | {pct(recall['linked'], recall['truth']):.2%} "
               f"({recall['linked']:,} / {recall['truth']:,}) |")
    out.append(f"| OBSERVED stage label correct (of correctly attributed) | {pct(obs['stage_correct'], obs['correct']):.2%} |")
    out.append(f"| CORRELATED stage label correct (of correctly attributed) | {pct(cor['stage_correct'], cor['correct']):.2%} |")
    out.append(f"| Individual stage statuses correct | {pct(stage_right['stage_ok'], stage_right['stages']):.2%} |")
    out.append(f"| First-failure component correct | {pct(stage_right['ff_component_ok'], stage_right['failed']):.2%} |")
    out.append("\n## By scenario\n")
    out.append("| Scenario | Flows | Stage status | Overall status | First failure |\n| --- | --- | --- | --- | --- |")
    for scenario in sorted(per_scenario):
        s = per_scenario[scenario]
        ff = f"{pct(s['ff_ok'], s['failed']):.1%}" if s["failed"] else "n/a"
        out.append(f"| {scenario} | {s['flows']} | {pct(s['status_ok'], s['flows']):.1%} | {pct(s['overall_ok'], s['flows']):.1%} | {ff} |")
    if not args.no_rules:
        out.append("\n## Rule detection (section 13)\n")
        out.append("| Scenario | Expected rule | Detected | Recall | Target ≥ 90% |\n| --- | --- | --- | --- | --- |")
        expected_of = dict(EXPECTED_RULES, BRUTE_FORCE_LOGIN={"R07"}, CONCURRENT_SESSIONS={"R08"}, AUTHZ_PROBING={"R14"},
                           SCANNER_TRAFFIC={"R15"})
        for scenario in sorted(s for s in rule_rows if s != "NORMAL"):
            c = rule_rows[scenario]
            r = pct(c["hit"], c["total"])
            out.append(f"| {scenario} | {', '.join(sorted(expected_of[scenario]))} | {c['hit']} / {c['total']} | {r:.1%} | "
                       f"{'yes' if r >= RULE_RECALL_TARGET else '**no**'} |")
        out.append("")
        out.append(f"False positives on NORMAL flows (any rule): {fp} / {normals} = {pct(fp, normals):.2%} "
                   f"(target ≤ {NORMAL_FP_TARGET:.0%}: {'yes' if met['normal_false_positives'] else '**no**'}); "
                   f"excluding the base-rate rules R09 and R12: {fp_strict} / {normals} = {pct(fp_strict, normals):.2%}.")
        fired = sorted((k, v) for k, v in rule_rows.get("NORMAL", {}).items() if k.startswith("fired:"))
        if fired:
            out.append("NORMAL flows that fired a rule, by rule set: " + ", ".join(f"{k[6:]} × {v}" for k, v in fired) + ".")
        for m in rule_misses[:25]:
            out.append(f"- rule miss: {m}")
        if len(rule_misses) > 25:
            out.append(f"- … and {len(rule_misses) - 25} more rule misses")
    out.append("\n## Misses\n")
    if not misses and not errors:
        out.append("None.")
    for m in misses[:25]:
        out.append(f"- `{m['flowRef']}` ({m['scenario']}): stages expected/got {m['stages'] or '-'}; "
                   f"first failure expected/got {m['firstFailure'] or '-'}")
    if len(misses) > 25:
        out.append(f"- … and {len(misses) - 25} more")
    for e in errors[:10]:
        out.append(f"- error: {e}")
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump({"metrics": metrics, "recall": pct(recall["linked"], recall["truth"]), "observed": dict(obs), "correlated": dict(cor),
                       "by_service": {k: dict(v) for k, v in by_service.items()}, "by_reason": dict(by_reason),
                       "flows": stage_right["flows"], "base": args.base, "finished": finished.isoformat()}, f, indent=2, sort_keys=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print("\n".join(out))
    return 0 if all(met.values()) and not errors else 1


if __name__ == "__main__":
    sys.exit(main())
