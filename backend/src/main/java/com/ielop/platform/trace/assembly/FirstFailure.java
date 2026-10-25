package com.ielop.platform.trace.assembly;

import java.time.Instant;

/**
 * The earliest line that made a stage FAILED (section 7.1 step 11). {@code component}
 * is the line's logger, or its service for lines without one (gateway, auth).
 */
public record FirstFailure(String stage, String component, String service, String exception, Integer httpStatus,
                           String docId, Instant timestamp) {
}
