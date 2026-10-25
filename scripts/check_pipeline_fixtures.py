"""PARSE-001 to PARSE-009 fixture checks for scripts/test-pipeline.sh (standard library only).

usage: check_pipeline_fixtures.py <cases.jsonl> <actual.jsonl> <hash key>

Each case lists the fields it expects, by dotted path. Special values:
  "$absent"          the field must not exist
  "$hmac:<value>"    HMAC-SHA256 of <value> with the test key, hex (the Logstash fingerprint)
Special keys:
  "$no_digits": "<n>"  the number must not appear in message or message_body (masking)
"""
import hashlib
import hmac
import json
import sys

MISSING = object()


def lookup(event, dotted):
    if dotted in event:
        return event[dotted]
    node = event
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            return MISSING
        node = node[part]
    return node


def main(cases_path, actual_path, key):
    cases = [json.loads(line) for line in open(cases_path, encoding="utf-8")]
    actual = {}
    for line in open(actual_path, encoding="utf-8"):
        event = json.loads(line)
        actual[event.get("test_case")] = event
    failures = 0
    for case in cases:
        event = actual.get(case["id"])
        if event is None:
            print(f"FAIL {case['id']}: no output event")
            failures += 1
            continue
        problems = []
        for field, want in case["expect"].items():
            if field == "$no_digits":
                for text_field in ("message", "message_body"):
                    if want in str(event.get(text_field, "")):
                        problems.append(f"{text_field} still contains {want}")
                continue
            got = lookup(event, field)
            if want == "$absent":
                if got is not MISSING:
                    problems.append(f"{field} should be absent, got {got!r}")
                continue
            if isinstance(want, str) and want.startswith("$hmac:"):
                want = hmac.new(key.encode(), want[6:].encode(), hashlib.sha256).hexdigest()
            if got is MISSING:
                problems.append(f"{field} missing (want {want!r})")
            elif got != want:
                problems.append(f"{field} = {got!r}, want {want!r}")
        if problems:
            failures += 1
            print(f"FAIL {case['id']}:")
            for p in problems:
                print(f"       {p}")
        else:
            print(f"ok   {case['id']}")
    print(f"{len(cases) - failures}/{len(cases)} fixture cases passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
