package com.ielop.platform.flowdef;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * One {@code match} entry: a line matches when its service equals {@code service}
 * and every other given criterion holds (HTTP method, URL path regex, event action).
 */
public record MatchRule(String service, String httpMethod, Pattern urlPath, Set<String> eventActions) {

    public boolean matches(String lineService, String lineMethod, String linePath, String lineAction) {
        if (!service.equals(lineService)) {
            return false;
        }
        if (httpMethod != null && !httpMethod.equals(lineMethod)) {
            return false;
        }
        if (urlPath != null && (linePath == null || !urlPath.matcher(linePath).find())) {
            return false;
        }
        return eventActions.isEmpty() || (lineAction != null && eventActions.contains(lineAction));
    }
}
