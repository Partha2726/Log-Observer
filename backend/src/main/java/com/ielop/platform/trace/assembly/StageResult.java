package com.ielop.platform.trace.assembly;

import java.time.Instant;

/** One stage of an assembled trace, in flow-definition order. */
public record StageResult(String name, int order, StageStatus status, String userId, Instant startedAt, Instant endedAt,
                          int errorCount, int lineCount) {
}
