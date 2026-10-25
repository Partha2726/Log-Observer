"""Import the approval limits of data/ground-truth/users.jsonl through POST /api/admin/user-limits/import (R10).

usage: python3 -I scripts/import_user_limits.py [--base URL] [--user U] [--password P] [--file PATH]
Uses the dev ADMINISTRATOR account by default (dev profile only). Standard library only.
"""
import argparse
import json
import os
import sys
import urllib.request
import uuid


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.environ.get("API_BASE_URL", "http://localhost:8080"))
    ap.add_argument("--user", default=os.environ.get("ADMIN_USER", "admin.dev"))
    ap.add_argument("--password", default=os.environ.get("ADMIN_PASSWORD", "DevPassword123!"))
    ap.add_argument("--file", default="data/ground-truth/users.jsonl")
    args = ap.parse_args()

    body = json.dumps({"username": args.user, "password": args.password}).encode()
    req = urllib.request.Request(f"{args.base}/api/auth/login", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        token = json.load(r)["accessToken"]

    boundary = uuid.uuid4().hex
    with open(args.file, "rb") as f:
        payload = f.read()
    data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"users.jsonl\"\r\n"
            f"Content-Type: application/x-ndjson\r\n\r\n").encode() + payload + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(f"{args.base}/api/admin/user-limits/import", data=data, method="POST", headers={
        "Authorization": f"Bearer {token}", "Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=60) as r:
        result = json.load(r)
    print(f"  imported {result['imported']} approval limits ({result['skipped']} users without one)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
