package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.Severity;
import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.logsearch.LogHit;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * R08: one user with two sessions from different client IPs that overlap in time. A session
 * runs from its sign-in success line to its sign-out or expiry line (the flow definitions'
 * session events); a session with no end line in the window is open until the window ends.
 */
@Component
public class R08ConcurrentSessions implements AnomalyRule {
    public String id() { return "R08"; }
    public String name() { return "Concurrent sessions"; }
    public Severity severity() { return Severity.MEDIUM; }
    public Scope scope() { return Scope.SCAN; }

    record Session(String id, String ip, Instant start, String docId) {
    }

    public List<Finding> evaluate(RuleInput in) {
        var ctx = in.context();
        Set<String> starts = new LinkedHashSet<>();
        Set<String> ends = new LinkedHashSet<>();
        for (FlowDefinition def : ctx.definitions()) {
            starts.addAll(def.signInSuccessEvents());
            ends.addAll(def.signOutEventActions());
        }
        if (starts.isEmpty()) {
            return List.of();
        }
        Map<String, List<Session>> byUser = new TreeMap<>();
        for (LogHit h : ctx.linesWithActions(starts, in.from(), in.to(), ctx.rules().getScanLineCap())) {
            if (h.userId() != null && h.sessionId() != null && h.clientIp() != null) {
                byUser.computeIfAbsent(h.userId(), k -> new ArrayList<>()).add(new Session(h.sessionId(), h.clientIp(), h.timestamp(), h.id()));
            }
        }
        // Only users seen from two or more IPs can have concurrent sessions from different IPs.
        byUser.values().removeIf(sessions -> sessions.stream().map(Session::ip).distinct().count() < 2);
        if (byUser.isEmpty()) {
            return List.of();
        }
        List<String> ids = byUser.values().stream().flatMap(List::stream).map(Session::id).distinct().toList();
        Map<String, Instant> endOf = new HashMap<>();
        for (LogHit h : ctx.sessionLines(ids, ends, in.from(), in.to(), ctx.rules().getScanLineCap())) {
            endOf.merge(h.sessionId(), h.timestamp(), (a, b) -> a.isBefore(b) ? a : b);
        }
        List<Finding> out = new ArrayList<>();
        byUser.forEach((user, sessions) -> {
            sessions.sort((a, b) -> a.start().equals(b.start()) ? a.id().compareTo(b.id()) : a.start().compareTo(b.start()));
            for (int i = 0; i < sessions.size(); i++) {
                Session a = sessions.get(i);
                Instant aEnd = endOf.getOrDefault(a.id(), in.to());
                for (int j = i + 1; j < sessions.size() && sessions.get(j).start().isBefore(aEnd); j++) {
                    Session b = sessions.get(j);
                    if (a.ip().equals(b.ip()) || a.id().equals(b.id())) {
                        continue;
                    }
                    Instant bEnd = endOf.getOrDefault(b.id(), in.to());
                    out.add(new Finding(id(), severity(), "R08:" + user + ":" + a.id() + ":" + b.id(), null, null, user, b.ip(),
                            b.start(), aEnd.isBefore(bEnd) ? aEnd : bEnd,
                            Map.of("sessions", List.of(a.id(), b.id()), "clientIps", List.of(a.ip(), b.ip())),
                            List.of(a.docId(), b.docId())));
                }
            }
        });
        return out.stream().collect(Collectors.toMap(Finding::dedupKey, f -> f, (x, y) -> x, java.util.LinkedHashMap::new))
                .values().stream().toList();
    }
}
