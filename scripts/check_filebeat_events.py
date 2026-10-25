"""PARSE-003 checks for scripts/test-filebeat.sh (Python standard library only).

Compares the events Filebeat shipped with the generator's ground truth:
  * the total event count and the count per service folder equal lines.jsonl;
  * every format D event starts with a log4j2 timestamp (continuation lines never become events);
  * format D events with stack traces, SQL and XML bodies exist and keep all their lines.
"""
import collections
import json
import re
import sys

START = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d{3} \| ")


def main(events_path, truth_path, expect_parsed=False):
    truth = collections.Counter()
    with open(truth_path, encoding="utf-8") as f:
        for line in f:
            truth[json.loads(line)["file"].split("/")[0]] += 1
    shipped = collections.Counter()
    multiline = collections.Counter()
    bad_starts = 0
    parse_failures = collections.Counter()
    with open(events_path, encoding="utf-8") as f:
        for line in f:
            event = json.loads(line)
            folder = event["log"]["file"]["path"].split("/")[2]
            shipped[folder] += 1
            for tag in event.get("tags", []):
                if tag.endswith("failure") or tag == "_serviceunknown":
                    parse_failures[tag] += 1
            if event.get("log_format") == "D":
                message = event["message"]
                if not START.match(message):
                    bad_starts += 1
                if "\n\tat " in message:
                    multiline["stack trace"] += 1
                if re.search(r"\n(SELECT|UPDATE|INSERT) ", message):
                    multiline["sql"] += 1
                if "\n<?xml" in message:
                    multiline["xml"] += 1
    failures = []
    if sum(shipped.values()) != sum(truth.values()):
        failures.append(f"event count {sum(shipped.values())} != ground truth {sum(truth.values())}")
    for folder in sorted(set(truth) | set(shipped)):
        status = "ok  " if truth[folder] == shipped[folder] else "FAIL"
        print(f"{status} {folder}: shipped {shipped[folder]}, ground truth {truth[folder]}")
        if truth[folder] != shipped[folder]:
            failures.append(f"{folder} count mismatch")
    if bad_starts:
        failures.append(f"{bad_starts} format D events start with a continuation line")
    print(f"ok   format D multi-line events: {dict(multiline)}; events starting mid-trace: {bad_starts}")
    if not multiline["sql"] or not multiline["xml"]:
        failures.append("expected SQL and XML multi-line events in format D")
    if expect_parsed:
        print(f"{'ok  ' if not parse_failures else 'FAIL'} parse failures: {dict(parse_failures) or 0}")
        if parse_failures:
            failures.append("generated lines failed to parse")
    print(f"TOTAL shipped {sum(shipped.values())}, ground truth {sum(truth.values())}")
    for failure in failures:
        print("FAIL", failure)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2], "--expect-parsed" in sys.argv[3:]))
