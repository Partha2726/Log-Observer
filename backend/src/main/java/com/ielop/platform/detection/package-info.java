/**
 * Anomaly rules R01 to R15 (spec section 10): the rule contract, the engine that runs
 * trace-scoped rules after every assembly and scan rules on a schedule, anomaly
 * storage with idempotent dedup keys, review, and user approval limits. Rules refer to
 * roles and stages by their position in the flow definition, never by demo names, and
 * every finding cites Elasticsearch document IDs. Rules never call the AI.
 */
package com.ielop.platform.detection;
