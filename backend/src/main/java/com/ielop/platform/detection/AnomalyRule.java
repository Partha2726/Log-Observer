package com.ielop.platform.detection;

import java.util.List;

/**
 * One anomaly rule (section 10): {@code evaluate} returns findings for a trace
 * ({@link Scope#TRACE}) or for a time window of the logs ({@link Scope#SCAN}).
 */
public interface AnomalyRule {

    enum Scope { TRACE, SCAN }

    String id();

    String name();

    Severity severity();

    Scope scope();

    List<Finding> evaluate(RuleInput in);
}
