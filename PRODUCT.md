# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Primary users are backend developers and DevOps engineers at software/SaaS companies who investigate incidents under time pressure. Their primary job is to rebuild one user interaction—a flow identified by a `flowRef`—as it crossed services that log in different formats, identify the first failure, and see who acted at each stage.

The secondary ADMINISTRATOR role has access to administration and audit workflows.

## Product Purpose

Log Observer reconstructs a business interaction end to end from logs. It helps experts move quickly from an ID or search term to evidence, a flow graph, the first failure, and the implicated actors.

It supports searching by user ID, flow reference, request ID, session, client IP, and free text; reviewing rule-detected anomalies; and using evidence-cited AI summaries and assistant answers as advisory investigation aids.

The product is read-only: it never changes or executes anything in the observed systems.

## Positioning

Log Observer follows a business flow rather than presenting an undifferentiated stream of log lines. Its stage model comes from reusable flow-definition files, so investigation is not tied to a single company or log format.

Every conclusion is evidence-backed: stage status, first failure, anomaly, and AI claim link to log lines. Evidence distinguishes `OBSERVED` from `CORRELATED` and carries confidence. Deterministic rules detect anomalies; AI only explains and must cite evidence.

## Operating Context

The core investigation view is a swimlane graph with the first failure clearly marked and an adjacent evidence panel. Typical work includes opening a flow from search, reviewing anomaly rules R01–R15 with a note, and verifying an AI overview or assistant answer against cited log lines.

Experts need dense, scannable tables and fast ID-to-evidence navigation rather than decorative monitoring-dashboard treatment.

## Capabilities and Constraints

- This is a cosmetic-only redesign: behavior, routes, API calls, state, backend-provided data, visible text and labels, and test attributes must not change.
- Existing frontend UI tests (UI-001 through UI-010) and Playwright E2E tests must continue to pass unchanged.
- The browser computes no status, confidence, anomaly, or graph structure; these are supplied by the backend.
- Graph nodes are keyboard-usable: Tab reaches nodes and Enter selects them.
- Data views retain loading, empty, error-with-Retry, and success states.
- All data is generated and the product must work fully offline.
- No new dependencies may be introduced without approval.

## Brand Commitments

There is no brand system and no real-company identity may be used. Acme Cloud is a fictional demonstration company.

The product should feel like a calm, utilitarian instrument panel—not a flashy monitoring dashboard or marketing page. Status colors are reserved for meaning, never decoration.

## Evidence on Hand

The repository contains generated demo data, flow definitions, frontend UI tests, and Playwright E2E tests. It contains no approved external brand assets. Fonts must be self-hosted; no CDNs or Google Fonts may be used.

## Product Principles

1. Lead users from an identifier to verifiable evidence quickly.
2. Make the first failure and its supporting evidence unmistakable.
3. Keep deterministic detection visibly distinct from advisory AI explanation.
4. Preserve the evidence trail and never conceal uncertainty.
5. Favor operational scanability and keyboard efficiency over visual flourish.

## Accessibility & Inclusion

- Target text contrast is at least 4.5:1, with visible focus rings.
- Color is never the sole status signal: statuses include icons and text labels.
- The graph legend continues to explain colors, dashed borders, and badges.
- Graph semantics are fixed: green means OK; red means first failure and uses a thick red border plus a `first failure` tag; amber means WARN; grey dashed means `NOT_REACHED`.
- Confidence below 1 uses a dashed border and displays its value.
- Times display the log-timezone label and show UTC on hover.
- Masked values remain masked, and AI text is always rendered as text rather than HTML.
