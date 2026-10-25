package com.ielop.platform.trace.assembly;

import com.ielop.platform.logsearch.LogHit;

import java.math.BigDecimal;

/** One linked line: its stage, evidence class, confidence and link reason; {@code seq} is its position in the trace order. */
public record TraceLink(LogHit hit, String stage, EvidenceClass evidenceClass, BigDecimal confidence, LinkReason reason, int seq) {
}
