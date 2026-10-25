package com.ielop.platform.logsearch;

import java.util.List;

/** All hits of a query up to a cap, in {@code (@timestamp, log.file.path, log.offset)} order. */
public record HitList(List<LogHit> hits, boolean truncated) {
}
