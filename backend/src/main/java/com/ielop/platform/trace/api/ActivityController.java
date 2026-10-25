package com.ielop.platform.trace.api;

import com.ielop.platform.trace.TraceAnomalies;
import org.springframework.beans.factory.ObjectProvider;

import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import com.ielop.platform.logsearch.LogCriteria;
import com.ielop.platform.logsearch.LogFields;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.logsearch.LogQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code GET /api/users/{userId}/activity} (section 7.3) and {@code GET /api/requests/{requestId}}.
 *
 * <p>Sign-in success and sign-out actions come from the flow definitions. Which actions
 * count as failed sign-ins and permission denials is configuration
 * ({@code app.activity.*}), so another company's events need no code change. A
 * sign-out line that belongs to an HTTP call (it has a request ID) is a LOGOUT; one
 * without (the server expired the session) is EXPIRED.</p>
 */
@RestController
@Tag(name = "Users and requests")
public class ActivityController {

    static final int CAP = 1_000;
    static final Duration FAILED_BEFORE_WINDOW = Duration.ofMinutes(10);

    private final LogQueryService logs;
    private final FlowDefinitionRegistry flows;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApiParams params;
    private final LineStages stages;
    private final Set<String> failedSignInActions;
    private final Set<String> permissionDeniedActions;
    private final ObjectProvider<TraceAnomalies> anomalies;

    public ActivityController(LogQueryService logs, FlowDefinitionRegistry flows, NamedParameterJdbcTemplate jdbc, ApiParams params,
                              LineStages stages, ObjectProvider<TraceAnomalies> anomalies,
                              @Value("${app.activity.failed-sign-in-actions:LOGIN_FAILED,MFA_FAILED}") String failed,
                              @Value("${app.activity.permission-denied-actions:AUTHZ_DENIED}") String denied) {
        this.logs = logs;
        this.flows = flows;
        this.jdbc = jdbc;
        this.params = params;
        this.stages = stages;
        this.anomalies = anomalies;
        this.failedSignInActions = csv(failed);
        this.permissionDeniedActions = csv(denied);
    }

    @Operation(summary = "Sessions, failed sign-ins, flows with the user's role, permission denials")
    @GetMapping("/api/users/{userId}/activity")
    public ApiDtos.UserActivity activity(@PathVariable String userId,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        ApiParams.Check check = params.check();
        check.userId("userId", userId, true);
        ApiParams.Window w = check.window(from, to);
        check.done();

        Set<String> successActions = new TreeSet<>();
        Set<String> signOutActions = new TreeSet<>();
        for (FlowDefinition d : flows.all()) {
            successActions.addAll(d.signInSuccessEvents());
            signOutActions.addAll(d.signOutEventActions());
        }

        // Failed sign-ins, also for counting attempts before each session.
        List<LogHit> failures = failedSignInActions.isEmpty() ? List.of() : logs.findAll("activity.failed",
                LogCriteria.builder().userId(userId).eventActions(failedSignInActions)
                        .between(w.from().minus(FAILED_BEFORE_WINDOW), w.to()).build(), CAP).hits();

        // Sessions: successful sign-ins, then their sign-out or expiry (possibly after the window).
        List<LogHit> logins = successActions.isEmpty() ? List.of() : logs.findAll("activity.sessions",
                LogCriteria.builder().userId(userId).eventActions(successActions).between(w.from(), w.to()).build(), CAP).hits();
        Map<String, LogHit> ends = new HashMap<>();
        List<String> sessionIds = logins.stream().map(LogHit::sessionId).filter(java.util.Objects::nonNull).distinct().toList();
        if (!sessionIds.isEmpty() && !signOutActions.isEmpty()) {
            for (LogHit end : logs.findAll("activity.session_ends", LogCriteria.builder().sessionIds(sessionIds)
                    .eventActions(signOutActions).build(), CAP).hits()) {
                ends.putIfAbsent(end.sessionId(), end);
            }
        }
        List<ApiDtos.Session> sessions = new ArrayList<>();
        for (LogHit login : logins) {
            LogHit end = login.sessionId() == null ? null : ends.get(login.sessionId());
            String reason = end == null ? "OPEN" : end.requestId() != null ? "LOGOUT" : "EXPIRED";
            int before = (int) failures.stream().filter(f -> !f.timestamp().isAfter(login.timestamp())
                    && !f.timestamp().isBefore(login.timestamp().minus(FAILED_BEFORE_WINDOW))).count();
            sessions.add(new ApiDtos.Session(login.sessionId(), login.timestamp(), end == null ? null : end.timestamp(), reason,
                    login.clientIp(), login.mfa(), before));
        }
        List<ApiDtos.FailedSignIn> failed = failures.stream().filter(f -> !f.timestamp().isBefore(w.from()))
                .map(f -> new ApiDtos.FailedSignIn(f.timestamp(), f.clientIp(), f.reason(), f.eventAction())).toList();

        // Flows: distinct flowRefs on the user's lines, with the user's role where the flow is assembled.
        Map<String, Long> refs = logs.terms("activity.flows", LogCriteria.builder().userId(userId).between(w.from(), w.to())
                .hasFlowRef(true).build(), LogFields.FLOW_REFS, CAP);
        List<ApiDtos.UserFlow> userFlows = userFlows(userId, refs.keySet(), w);

        List<ApiDtos.PermissionDenial> denials = permissionDeniedActions.isEmpty() ? List.of() : logs.findAll("activity.denials",
                        LogCriteria.builder().userId(userId).eventActions(permissionDeniedActions).between(w.from(), w.to()).build(), CAP)
                .hits().stream().map(h -> new ApiDtos.PermissionDenial(h.timestamp(), h.permission(), h.resource())).toList();

        TraceAnomalies source = anomalies.getIfAvailable();
        List<TraceAnomalies.View> userAnomalies = source == null ? List.of() : source.forUser(userId, w.from(), w.to());
        return new ApiDtos.UserActivity(userId, w.from(), w.to(), sessions, failed, userFlows, denials, userAnomalies);
    }

    private List<ApiDtos.UserFlow> userFlows(String userId, Set<String> refs, ApiParams.Window w) {
        List<String> known = refs.stream().filter(r -> flows.forFlowRef(r).isPresent()).sorted().toList();
        if (known.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> traces = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT id, flow_ref, flow_id, overall_status, first_seen FROM trace WHERE flow_ref IN (:refs)",
                new MapSqlParameterSource("refs", known))) {
            traces.put((String) row.get("flow_ref"), row);
        }
        Map<String, List<String>> roles = new HashMap<>();
        jdbc.query("SELECT t.flow_ref, a.role FROM trace_actor a JOIN trace t ON t.id = a.trace_id WHERE t.flow_ref IN (:refs) AND a.user_id = :user",
                new MapSqlParameterSource("refs", known).addValue("user", userId), rs -> {
                    roles.computeIfAbsent(rs.getString("flow_ref"), k -> new ArrayList<>()).add(rs.getString("role"));
                });
        List<ApiDtos.UserFlow> out = new ArrayList<>();
        for (String ref : known) {
            FlowDefinition def = flows.forFlowRef(ref).orElseThrow();
            Map<String, Object> t = traces.get(ref);
            List<String> userRoles = roles.getOrDefault(ref, List.of());
            // The user's first role in definition order (a requester who also executes is shown as requester).
            String role = def.roles().stream().filter(userRoles::contains).findFirst().orElse(null);
            Instant ts = t == null ? null : ((java.sql.Timestamp) t.get("first_seen")).toInstant();
            out.add(new ApiDtos.UserFlow(ref, def.id(), role, t == null ? null : (String) t.get("overall_status"), ts));
        }
        out.sort(Comparator.comparing(ApiDtos.UserFlow::ts, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ApiDtos.UserFlow::flowRef));
        return out;
    }

    @Operation(summary = "Resolve a request ID to its flowRef, or return the request's own lines")
    @GetMapping("/api/requests/{requestId}")
    public ApiDtos.RequestLookup request(@PathVariable String requestId) {
        ApiParams.Check check = params.check();
        check.requestId("requestId", requestId);
        check.done();

        List<LogHit> lines = logs.findAll("requests.lookup", LogCriteria.builder().requestId(requestId).build(), 200).hits();
        Set<String> refs = new TreeSet<>();
        lines.forEach(h -> refs.addAll(h.flowRefs()));
        jdbc.queryForList("SELECT DISTINCT t.flow_ref FROM trace_event_link l JOIN trace t ON t.id = l.trace_id WHERE l.request_id = :rid",
                new MapSqlParameterSource("rid", requestId), String.class).forEach(refs::add);
        if (lines.isEmpty() && refs.isEmpty()) {
            throw com.ielop.platform.common.exception.ApiException.notFound("No lines carry request ID " + requestId);
        }
        Map<String, String> stageOf = stages.stagesOf(lines);
        List<ApiDtos.LogLine> view = lines.stream().map(h -> SearchController.line(h, stageOf.get(h.id()), null)).toList();
        List<String> refList = List.copyOf(refs);
        return new ApiDtos.RequestLookup(requestId, refList.isEmpty() ? null : refList.get(0), refList, view);
    }

    private static Set<String> csv(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
