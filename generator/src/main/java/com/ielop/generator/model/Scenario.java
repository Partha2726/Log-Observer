package com.ielop.generator.model;

/** Ground-truth scenario labels (spec section 5.5). */
public enum Scenario {
    NORMAL,
    FAIL_DB_TIMEOUT_APPROVE,
    FAIL_DB_DOWN_CREATE,
    FAIL_VALIDATION_CREATE,
    FAIL_PROVIDER_TIMEOUT_EXECUTE,
    SELF_APPROVAL,
    EXECUTE_WITHOUT_APPROVAL,
    OUT_OF_ORDER,
    BRUTE_FORCE_LOGIN,
    CONCURRENT_SESSIONS,
    SLOW_APPROVE,
    LIMIT_BREACH,
    DUPLICATE_SUBMIT,
    OFF_HOURS,
    APPROVER_REJECT,
    SESSION_TIMEOUT,
    AUTHZ_PROBING,
    SCANNER_TRAFFIC;

    /** Scenarios whose signature is an incident around the flow (a user or client IP), not the flow itself. */
    public boolean isIncident() {
        return this == BRUTE_FORCE_LOGIN || this == CONCURRENT_SESSIONS || this == AUTHZ_PROBING || this == SCANNER_TRAFFIC;
    }
}
