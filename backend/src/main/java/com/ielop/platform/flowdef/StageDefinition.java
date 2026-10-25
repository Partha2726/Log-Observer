package com.ielop.platform.flowdef;

import java.util.List;
import java.util.Set;

/**
 * One stage, in flow-definition order ({@code order} starts at 1). A stage
 * without {@code actorRole} is a session stage: each actor's own sign-in.
 */
public record StageDefinition(
        String name,
        int order,
        String actorRole,
        boolean canReject,
        List<MatchRule> match,
        Set<String> successEvents,
        Set<String> rejectEvents
) {
    public boolean matches(String service, String method, String path, String action) {
        return match.stream().anyMatch(m -> m.matches(service, method, path, action));
    }

    public boolean isSessionStage() {
        return actorRole == null;
    }

    /** Event actions named by this stage's matchers. */
    public Set<String> eventActions() {
        return match.stream().flatMap(m -> m.eventActions().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
