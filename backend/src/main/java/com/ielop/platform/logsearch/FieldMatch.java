package com.ielop.platform.logsearch;

import java.util.Set;

/**
 * One "line looks like this" condition: service equals, and optionally HTTP method
 * equals, URL path matches a Lucene regular expression (implicitly anchored), and
 * event action is one of a set. Used to filter by a flow definition's stage matchers.
 */
public record FieldMatch(String service, String httpMethod, String urlPathRegexp, Set<String> eventActions) {
}
