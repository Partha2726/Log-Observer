"""Queue a rule scan through POST /api/admin/rule-scan and print the job ID (standard library only).

usage: python3 -I scripts/rule_scan.py --from ISO --to ISO [--base URL] [--user U] [--password P]
Uses the dev ADMINISTRATOR account by default (dev profile only). While another scan is queued or
running (409), it retries for up to five minutes.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="start", required=True)
    ap.add_argument("--to", dest="end", required=True)
    ap.add_argument("--base", default=os.environ.get("API_BASE_URL", "http://localhost:8080"))
    ap.add_argument("--user", default=os.environ.get("ADMIN_USER", "admin.dev"))
    ap.add_argument("--password", default=os.environ.get("ADMIN_PASSWORD", "DevPassword123!"))
    args = ap.parse_args()

    body = json.dumps({"username": args.user, "password": args.password}).encode()
    req = urllib.request.Request(f"{args.base}/api/auth/login", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        token = json.load(r)["accessToken"]

    query = urllib.parse.urlencode({"from": args.start, "to": args.end})
    deadline = time.time() + 300
    while True:
        req = urllib.request.Request(f"{args.base}/api/admin/rule-scan?{query}", data=b"", method="POST",
                                     headers={"Authorization": f"Bearer {token}"})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                print(json.load(r)["jobId"])
                return 0
        except urllib.error.HTTPError as e:
            if e.code != 409 or time.time() > deadline:
                raise
            time.sleep(5)


if __name__ == "__main__":
    sys.exit(main())
