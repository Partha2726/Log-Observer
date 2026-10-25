"""Phase 3 comparison (spec section 15, T33): attribution with and without OpenTelemetry trace IDs on the same seed.

Reads two JSON files written by `evaluation/score.py --json-out` (one run generated with `--otel false`,
one with `--otel true`, same seed and size) and writes the "OpenTelemetry comparison" section into
evaluation/report.md (replacing an earlier one). Standard library only.

usage: python3 evaluation/compare_otel.py <without.json> <with.json> [--report evaluation/report.md]
"""
import argparse
import json
import sys

SECTION = "## OpenTelemetry comparison (phase 3)"
SERVICES = ["gateway", "auth-service", "workflow-service", "billing-service"]


def pct(n, d):
    return n / d if d else 1.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("without")
    ap.add_argument("with_otel")
    ap.add_argument("--report", default="evaluation/report.md")
    args = ap.parse_args()
    a = json.load(open(args.without, encoding="utf-8"))
    b = json.load(open(args.with_otel, encoding="utf-8"))

    def row(label, va, vb):
        return f"| {label} | {va:.2%} | {vb:.2%} | {(vb - va) * 100:+.2f} pp |"

    out = [SECTION, "",
           f"Same seed and dataset size ({a['flows']:,} and {b['flows']:,} flows scored), generated once with `--otel false` and once "
           "with `--otel true`, each through `make demo` and `make eval`. With OpenTelemetry, assembly also links every line that "
           "shares a flow line's `trace.id` as OBSERVED (link reason `TRACE`).", "",
           "| Measure | Without `trace.id` | With `trace.id` | Change |", "| --- | --- | --- | --- |",
           row("OBSERVED lines correct (precision)", a["metrics"]["observed_precision"], b["metrics"]["observed_precision"]),
           row("CORRELATED precision", a["metrics"]["correlated_precision"], b["metrics"]["correlated_precision"]),
           row("Line recall (ground-truth lines linked)", a["recall"], b["recall"]),
           row("Stage status (all stages exact)", a["metrics"]["stage_status_accuracy"], b["metrics"]["stage_status_accuracy"]),
           row("First-failure stage", a["metrics"]["first_failure_stage_accuracy"], b["metrics"]["first_failure_stage_accuracy"])]
    obs_share_a = pct(a["observed"].get("total", 0), a["observed"].get("total", 0) + a["correlated"].get("total", 0))
    obs_share_b = pct(b["observed"].get("total", 0), b["observed"].get("total", 0) + b["correlated"].get("total", 0))
    out.append(row("Share of linked lines that are OBSERVED", obs_share_a, obs_share_b))
    out += ["", "By service (lines of the scored flows):", "",
            "| Service | Recall without | Recall with | OBSERVED share without | OBSERVED share with | Precision without | Precision with |",
            "| --- | --- | --- | --- | --- | --- | --- |"]
    for s in SERVICES:
        x = a["by_service"].get(s, {})
        y = b["by_service"].get(s, {})
        out.append(f"| {s} | {pct(x.get('correct', 0), x.get('truth', 0)):.2%} | {pct(y.get('correct', 0), y.get('truth', 0)):.2%} | "
                   f"{pct(x.get('observed', 0), x.get('linked', 0)):.2%} | {pct(y.get('observed', 0), y.get('linked', 0)):.2%} | "
                   f"{pct(x.get('correct', 0), x.get('linked', 0)):.2%} | {pct(y.get('correct', 0), y.get('linked', 0)):.2%} |")
    reasons = sorted(set(a["by_reason"]) | set(b["by_reason"]))
    out += ["", "Links by reason: " + "; ".join(f"`{r}` {a['by_reason'].get(r, 0):,} → {b['by_reason'].get(r, 0):,}" for r in reasons) + ".", ""]

    try:
        report = open(args.report, encoding="utf-8").read()
    except FileNotFoundError:
        report = ""
    if SECTION in report:
        start = report.index(SECTION)
        nxt = report.find("\n## ", start + len(SECTION))
        report = report[:start] + report[nxt + 1:] if nxt >= 0 else report[:start]
    report = report.rstrip("\n") + "\n\n" + "\n".join(out)
    with open(args.report, "w", encoding="utf-8") as f:
        f.write(report)
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
