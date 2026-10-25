package com.ielop.platform.flowdef;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A loaded, validated flow definition. {@code sha256} is the SHA-256 of the file's
 * bytes and is part of every trace's configuration hash (section 7.2).
 */
public record FlowDefinition(
        String id,
        int version,
        String refPatternSource,
        Pattern refPattern,
        List<String> roles,
        List<StageDefinition> stages,
        List<MatchRule> signOut,
        Map<String, String> businessFields,
        String sha256,
        String source
) {
    /** Reserved stage label for sign-out and expiry lines (the definition's {@code signOut} block). */
    public static final String SIGN_OUT = "signOut";

    public boolean matchesRef(String flowRef) {
        return flowRef != null && refPattern.matcher(flowRef).matches();
    }

    public Optional<StageDefinition> stage(String name) {
        return stages.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    public StageDefinition finalStage() {
        return stages.get(stages.size() - 1);
    }

    /** First stage whose matchers accept the line, in definition order. */
    public Optional<StageDefinition> stageOf(String service, String method, String path, String action) {
        return stages.stream().filter(s -> s.matches(service, method, path, action)).findFirst();
    }

    public boolean isSignOut(String service, String method, String path, String action) {
        return signOut.stream().anyMatch(m -> m.matches(service, method, path, action));
    }

    /** Event actions of the session stages and of sign-out: an actor's sign-in, sign-out and expiry. */
    public Set<String> sessionEventActions() {
        Set<String> out = new HashSet<>();
        stages.stream().filter(StageDefinition::isSessionStage).forEach(s -> out.addAll(s.eventActions()));
        signOut.forEach(m -> out.addAll(m.eventActions()));
        return Set.copyOf(out);
    }

    /** Success events of the session stages (a successful sign-in). */
    public Set<String> signInSuccessEvents() {
        Set<String> out = new HashSet<>();
        stages.stream().filter(StageDefinition::isSessionStage).forEach(s -> out.addAll(s.successEvents()));
        return Set.copyOf(out);
    }

    public Set<String> signOutEventActions() {
        Set<String> out = new HashSet<>();
        signOut.forEach(m -> out.addAll(m.eventActions()));
        return Set.copyOf(out);
    }
}
