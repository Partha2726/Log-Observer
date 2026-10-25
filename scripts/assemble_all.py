"""Assembles every flow found in Elasticsearch through the backend API (standard library only).

Phase 1 stand-in for the bulk re-assembly job (POST /api/admin/reassemble arrives in T30): lists the
distinct flow.refs of logs-flow-* with a composite aggregation as the read-only backend_reader user,
then calls GET /api/traces/{flowRef} for each, several at a time. Uses the product's own data, never
the ground truth.

usage: assemble_all.py [--es URL] [--api URL] [--threads N]
"""
import argparse
import base64
import concurrent.futures
import json
import os
import sys
import time
import urllib.request


def es_refs(es, user, password, index):
    auth = base64.b64encode(f"{user}:{password}".encode()).decode()
    after = None
    while True:
        composite = {"size": 1000, "sources": [{"ref": {"terms": {"field": "flow.refs"}}}]}
        if after:
            composite["after"] = after
        body = json.dumps({"size": 0, "aggs": {"refs": {"composite": composite}}}).encode()
        req = urllib.request.Request(f"{es}/{index}/_search", data=body,
                                     headers={"Content-Type": "application/json", "Authorization": f"Basic {auth}"})
        with urllib.request.urlopen(req, timeout=60) as r:
            agg = json.load(r)["aggregations"]["refs"]
        for bucket in agg["buckets"]:
            yield bucket["key"]["ref"]
        after = agg.get("after_key")
        if not agg["buckets"] or not after:
            return


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--es", default="http://localhost:9200")
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--threads", type=int, default=4)
    args = ap.parse_args()

    refs = list(es_refs(args.es, os.environ.get("ES_USERNAME", "backend_reader"), os.environ["ES_PASSWORD"],
                        os.environ.get("ES_INDEX_PATTERN", "logs-flow-*")))
    login = urllib.request.Request(f"{args.api}/api/auth/login", headers={"Content-Type": "application/json"},
                                   data=json.dumps({"username": "devops.dev", "password": "DevPassword123!"}).encode())
    with urllib.request.urlopen(login, timeout=30) as r:
        token = json.load(r)["accessToken"]

    def assemble(ref):
        req = urllib.request.Request(f"{args.api}/api/traces/{ref}", headers={"Authorization": f"Bearer {token}"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)["overallStatus"]
        except Exception as e:  # noqa: BLE001
            return f"error: {e}"

    start = time.time()
    statuses = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as pool:
        for i, (ref, status) in enumerate(zip(refs, pool.map(assemble, refs)), 1):
            statuses[status] = statuses.get(status, 0) + 1
            if i % 1000 == 0:
                print(f"  assembled {i}/{len(refs)}", file=sys.stderr)
    print(f"assembled {len(refs)} flows in {time.time() - start:.0f} s: {dict(sorted(statuses.items()))}")
    return 1 if any(k.startswith("error") for k in statuses) else 0


if __name__ == "__main__":
    sys.exit(main())
