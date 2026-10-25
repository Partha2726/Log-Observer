package com.ielop.generator.model;

/**
 * The four Acme Cloud services and the folder each writes to (spec section 3).
 */
public enum Service {
    /** Format A, NCSA combined access log. */
    GATEWAY("gateway"),
    /** Format B, JSON lines. */
    WORKFLOW("workflow-service"),
    /** Format C, syslog RFC 5424. */
    AUTH("auth-service"),
    /** Format D, log4j2 plain text, multi-line. */
    BILLING("billing-service");

    private final String folder;

    Service(String folder) {
        this.folder = folder;
    }

    public String folder() {
        return folder;
    }
}
