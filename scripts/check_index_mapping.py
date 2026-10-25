"""ES-001: an index created from deploy/elasticsearch/index-template.json has the field types of
spec sections 6.3 and 6.4 (standard library only). Reads GET <index>/_mapping JSON on stdin."""
import json
import sys

EXPECTED = {
    "@timestamp": "date", "ingested_at": "date",
    "service.name": "keyword", "host.name": "keyword", "log_format": "keyword",
    "log.level": "keyword", "log.logger": "keyword", "process.thread.name": "keyword", "method": "keyword",
    "line": "integer",
    "flow.refs": "keyword", "user.id": "keyword", "session.id": "keyword", "http.request.id": "keyword",
    "flow.external_correlation_id": "keyword", "event.action": "keyword",
    "client.ip": "ip", "http.request.method": "keyword", "url.original": "keyword", "url.path": "keyword",
    "http.response.status_code": "integer", "event.duration_s": "float", "user_agent.original": "keyword",
    "error.type": "keyword", "flow.provider_status": "keyword", "error.stack_trace": "text",
    "is_error": "boolean", "is_warning": "boolean", "has_stacktrace": "boolean",
    "message_body": "text", "message_body.keyword": "keyword", "message": "text",
    "trace.id": "keyword", "span.id": "keyword",
    "flow.amount": "scaled_float", "flow.limit_amount": "scaled_float", "flow.currency": "keyword",
    "flow.counterparty_hash": "keyword", "flow.counterparty_last4": "keyword", "flow.reason": "keyword",
    "flow.permission": "keyword", "flow.resource": "keyword", "log.offset": "long", "log.file.path": "keyword",
}


def flatten(properties, prefix=""):
    out = {}
    for name, spec in properties.items():
        path = prefix + name
        if "type" in spec:
            out[path] = spec
        if "properties" in spec:
            out.update(flatten(spec["properties"], path + "."))
        for sub, sub_spec in spec.get("fields", {}).items():
            out[path + "." + sub] = sub_spec
    return out


def main():
    mapping = json.load(sys.stdin)
    index = next(iter(mapping.values()))
    fields = flatten(index["mappings"]["properties"])
    failures = []
    for field, want in EXPECTED.items():
        got = fields.get(field, {}).get("type")
        if got != want:
            failures.append(f"{field}: {got} (want {want})")
    checks = [
        ("user_agent.original ignore_above 512", fields.get("user_agent.original", {}).get("ignore_above") == 512),
        ("message_body.keyword ignore_above 1024", fields.get("message_body.keyword", {}).get("ignore_above") == 1024),
        ("error.stack_trace not indexed", fields.get("error.stack_trace", {}).get("index") is False),
        ("flow.amount scaling_factor 100", fields.get("flow.amount", {}).get("scaling_factor") == 100),
    ]
    failures += [name for name, ok in checks if not ok]
    for f in failures:
        print("FAIL ES-001", f)
    if not failures:
        print(f"ok   ES-001 {len(EXPECTED)} field types and {len(checks)} field options match sections 6.3 and 6.4")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
