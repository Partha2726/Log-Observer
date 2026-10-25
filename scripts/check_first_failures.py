"""Checks the graph's first-failure node against ground truth for sampled failed flows (T17 acceptance).

Standard library only. Logs in as the dev user, opens each trace through the API (assembling it if
needed) and compares the node marked isFirstFailure with expectedFailingStage and
expectedFailingComponent from data/ground-truth/flows.jsonl.

usage: check_first_failures.py [count] [base url]
"""
import json
import random
import sys
import urllib.request

def call(url, token=None, body=None):
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body else None,
                                 headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)

def main(count="10", base="http://localhost:8080"):
    flows = [json.loads(l) for l in open("data/ground-truth/flows.jsonl", encoding="utf-8")]
    failed = [f for f in flows if f["overallStatus"] == "FAILED"]
    sample = random.Random(42).sample(failed, min(int(count), len(failed)))
    token = call(f"{base}/api/auth/login", body={"username": "devops.dev", "password": "DevPassword123!"})["accessToken"]
    ok = 0
    for f in sample:
        ref = f["flowRef"]
        call(f"{base}/api/traces/{ref}", token)
        graph = call(f"{base}/api/traces/{ref}/graph", token)
        marked = [n for n in graph["nodes"] if n["isFirstFailure"]]
        component = (marked[0].get("logger") or marked[0].get("service")) if len(marked) == 1 else None
        stage = marked[0]["stage"] if len(marked) == 1 else None
        good = stage == f["expectedFailingStage"] and component == f["expectedFailingComponent"]
        ok += good
        print(f"{'ok  ' if good else 'FAIL'} {ref} {f['scenario']}: node {stage}/{component}, "
              f"truth {f['expectedFailingStage']}/{f['expectedFailingComponent']}")
    print(f"{ok}/{len(sample)} first-failure nodes match ground truth")
    return 0 if ok == len(sample) else 1

if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
