package com.ielop.platform.logsearch;

import java.util.List;
import java.util.Map;

/**
 * One page of hits with a total that is capped at {@code track_total_hits}
 * ({@code totalIsLowerBound} is then true, spec section 8.7). {@code highlights}
 * maps document IDs to their highlighted message fragment.
 */
public record HitPage(List<LogHit> hits, long total, boolean totalIsLowerBound, Map<String, String> highlights) {
}
