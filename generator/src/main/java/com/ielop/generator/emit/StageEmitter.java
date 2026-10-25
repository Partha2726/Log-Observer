package com.ielop.generator.emit;

import com.ielop.generator.flow.FlowDefinition;
import com.ielop.generator.model.Flow;
import com.ielop.generator.model.Incident;
import com.ielop.generator.model.Scenario;
import com.ielop.generator.model.Session;
import com.ielop.generator.model.User;
import com.ielop.generator.model.UserDirectory;
import org.apache.logging.log4j.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;

import static com.ielop.generator.emit.MessageCatalogue.*;

/**
 * Builds the events of one flow across the four services (spec sections 2.4,
 * 5.4 and 5.7): each actor's sign-in, the stages, and each actor's sign-out.
 *
 * <p>Every HTTP call goes through the gateway with a fresh request ID that the
 * auth and workflow services always log and the legacy billing service logs at
 * {@link #BILLING_REQUEST_ID_RATE}. The flowRef is on every workflow line from
 * the form opening onwards, in the gateway URL of approve and execute calls,
 * and in {@link #BILLING_FLOW_REF_RATE} of billing messages.</p>
 */
public final class StageEmitter {

    /** Section 5.4: the legacy service loses its MDC on the worker pool. */
    public static final double BILLING_REQUEST_ID_RATE = 0.60;
    /** Section 5.4: share of billing messages that mention the flowRef. */
    public static final double BILLING_FLOW_REF_RATE = 0.40;
    /** Section 5.4: SQL statements continue about 20% of billing lines. */
    public static final double SQL_RATE = 0.20;
    /** Idle minutes before the auth service expires a session (section 2.4). */
    public static final int SESSION_IDLE_MINUTES = 15;
    /** Reserved stage label for sign-out and expiry lines (the flow definition's {@code signOut} block). */
    public static final String SIGN_OUT = "signOut";

    private final FlowDefinition definition;
    private final MessageCatalogue catalogue;
    private final UserDirectory users;
    private final Consumer<Event.Builder> out;

    public StageEmitter(FlowDefinition definition, MessageCatalogue catalogue, UserDirectory users,
                        Consumer<Event.Builder> out) {
        this.definition = definition;
        this.catalogue = catalogue;
        this.users = users;
        this.out = out;
    }

    /** Ground-truth labels and randomness for the lines being built. */
    record Ctx(String flowRef, SplittableRandom rnd) {
    }

    // ------------------------------------------------------------------ flows

    /** Supplies earlier, finished flowRefs for permission probing (or guessed ones early in a run). */
    public interface ProbeTargets {
        List<String> refs(long before, int count, SplittableRandom rnd);
    }

    private Consumer<Incident> incidents = i -> { };
    private ProbeTargets probeTargets = (before, count, rnd) -> List.of();

    public void incidents(Consumer<Incident> sink) {
        this.incidents = sink;
    }

    public void probeTargets(ProbeTargets targets) {
        this.probeTargets = targets;
    }

    /**
     * One flow of the given scenario (section 5.5) and, for incident scenarios, the
     * incident around it. Returns every flow written: usually just {@code flow}, plus
     * the second submission of DUPLICATE_SUBMIT ({@code duplicate} must then be given).
     */
    public List<Flow> emitFlow(Flow flow, Flow duplicate, SplittableRandom rnd) {
        Ctx c = new Ctx(flow.flowRef(), rnd);
        Scenario scenario = flow.scenario();
        User requester = flow.requester();
        String signInStage = definition.signInStage();
        String create = definition.createStage();
        String approveStage = definition.approveStage();
        String executeStage = definition.executeStage();
        List<Flow> written = new ArrayList<>(List.of(flow));
        long t0 = flow.startMillis();

        // Incidents start with the flow's slot; the flow itself then runs as NORMAL.
        String signInIp = requester.homeIp();
        long flowStart = t0;
        switch (scenario) {
            case BRUTE_FORCE_LOGIN -> {
                signInIp = "203.0.113." + (10 + rnd.nextInt(200));
                flowStart = bruteForce(rnd, requester, signInIp, t0, flow.flowRef());
            }
            case AUTHZ_PROBING -> probing(rnd, t0, flow.flowRef());
            case SCANNER_TRAFFIC -> scanner(rnd, t0, flow.flowRef());
            default -> { }
        }

        // SignIn and Create (requester)
        Session reqSession = signIn(c, requester, flowStart, signInStage, signInIp);
        flow.status(signInStage, "COMPLETED");
        if (scenario == Scenario.CONCURRENT_SESSIONS) {
            concurrentSession(rnd, requester, reqSession.lastActivityMillis() + between(rnd, 1_000, 10_000), flow.flowRef());
        }
        Session earlyApprover = null;
        long earlyApproveEnd = 0;
        if (scenario == Scenario.OUT_OF_ORDER) {
            // Approve is logged before Create (clock skew or a replayed call): R04.
            User approver = users.approverFor(flow.amount(), requester, rnd);
            flow.approver(approver);
            earlyApprover = signIn(c, approver, reqSession.lastActivityMillis() + between(rnd, 500, 3_000), signInStage);
            earlyApproveEnd = approve(c, flow, earlyApprover, earlyApprover.lastActivityMillis() + between(rnd, 1_000, 5_000));
        }
        long t = Math.max(reqSession.lastActivityMillis(), earlyApproveEnd) + between(rnd, 2_000, 30_000);
        long formEnd = openForm(c, reqSession, t);
        long submitAt = formEnd + between(rnd, 3_000, 20_000);
        long createEnd = switch (scenario) {
            case FAIL_DB_DOWN_CREATE -> submitDatabaseDown(c, reqSession, submitAt);
            case FAIL_VALIDATION_CREATE -> submitInvalid(c, reqSession, submitAt);
            default -> submit(c, flow, reqSession, submitAt);
        };
        if (scenario == Scenario.DUPLICATE_SUBMIT) {
            // The same request again within 60 s, as a second flowRef in the same session (R11).
            Ctx d = new Ctx(duplicate.flowRef(), rnd);
            long dupForm = openForm(d, reqSession, createEnd + between(rnd, 2_000, 15_000));
            createEnd = submit(d, duplicate, reqSession, dupForm + between(rnd, 2_000, 15_000));
            duplicate.status(signInStage, "COMPLETED");
            duplicate.status(create, "COMPLETED");
            written.add(duplicate);
        }
        if (scenario == Scenario.SESSION_TIMEOUT) {
            expire(c, reqSession);
        } else {
            signOut(c, reqSession, createEnd + between(rnd, 5_000, 120_000));
        }
        if (scenario == Scenario.FAIL_DB_DOWN_CREATE || scenario == Scenario.FAIL_VALIDATION_CREATE) {
            flow.status(create, "FAILED");
            flow.status(approveStage, "NOT_REACHED");
            flow.status(executeStage, "NOT_REACHED");
            flow.failure(create, scenario == Scenario.FAIL_DB_DOWN_CREATE ? WF_REQUEST : "gateway");
            return written;
        }
        flow.status(create, "COMPLETED");

        if (scenario == Scenario.EXECUTE_WITHOUT_APPROVAL) {
            // A direct execute call with no approval at all (R03).
            User executor = users.otherApprover(rnd, requester);
            flow.executor(executor);
            Session execSession = signIn(c, executor, createEnd + between(rnd, 60_000, 3_600_000), signInStage);
            long execEnd = execute(c, flow, execSession, execSession.lastActivityMillis() + between(rnd, 3_000, 30_000));
            flow.status(approveStage, "NOT_REACHED");
            flow.status(executeStage, "COMPLETED");
            signOut(c, execSession, execEnd + between(rnd, 5_000, 120_000));
            return written;
        }

        // Approve (a different user unless SELF_APPROVAL, minutes to hours later)
        User approver = switch (scenario) {
            case SELF_APPROVAL -> requester;
            case LIMIT_BREACH -> users.approverBelow(flow.amount(), requester, rnd);
            case OUT_OF_ORDER -> flow.approver();
            default -> users.approverFor(flow.amount(), requester, rnd);
        };
        flow.approver(approver);
        Session appSession;
        long approveEnd;
        if (scenario == Scenario.OUT_OF_ORDER) {
            appSession = earlyApprover;
            approveEnd = createEnd;
        } else {
            long approveAt = scenario == Scenario.OFF_HOURS
                    ? offHours(createEnd, rnd)
                    : createEnd + between(rnd, 60_000, 4 * 3_600_000);
            appSession = signIn(c, approver, approveAt, signInStage);
            long approveCallAt = appSession.lastActivityMillis() + between(rnd, 3_000, 60_000);
            if (scenario == Scenario.FAIL_DB_TIMEOUT_APPROVE || scenario == Scenario.APPROVER_REJECT) {
                long end = scenario == Scenario.APPROVER_REJECT
                        ? reject(c, flow, appSession, approveCallAt)
                        : approveDatabaseTimeout(c, flow, appSession, approveCallAt);
                signOut(c, appSession, end + between(rnd, 5_000, 60_000));
                flow.status(approveStage, scenario == Scenario.APPROVER_REJECT ? "REJECTED" : "FAILED");
                flow.status(executeStage, "NOT_REACHED");
                if (scenario == Scenario.FAIL_DB_TIMEOUT_APPROVE) {
                    flow.failure(approveStage, abbreviate(BL_LIMIT_CHECK));
                }
                return written;
            }
            approveEnd = scenario == Scenario.SLOW_APPROVE
                    ? approveSlowly(c, flow, appSession, approveCallAt)
                    : approve(c, flow, appSession, approveCallAt);
            if (scenario == Scenario.DUPLICATE_SUBMIT) {
                duplicate.approver(approver);
                approveEnd = approve(new Ctx(duplicate.flowRef(), rnd), duplicate, appSession, approveEnd + between(rnd, 5_000, 60_000));
                duplicate.status(approveStage, "COMPLETED");
            }
        }
        flow.status(approveStage, "COMPLETED");

        // Execute (often the approver), 0 to 60 minutes later
        User executor = scenario == Scenario.DUPLICATE_SUBMIT || scenario == Scenario.OFF_HOURS || rnd.nextInt(10) < 7
                ? approver : users.otherApprover(rnd, requester, approver);
        flow.executor(executor);
        long gap = scenario == Scenario.OFF_HOURS ? between(rnd, 60_000, 30 * 60_000) : between(rnd, 0, 3_600_000);
        Session execSession;
        long execAt;
        if (executor == approver && gap <= 10 * 60_000 && scenario != Scenario.OUT_OF_ORDER) {
            execSession = appSession;
            execAt = approveEnd + Math.max(gap, 2_000);
        } else {
            signOut(c, appSession, approveEnd + between(rnd, 5_000, 60_000));
            long signInAt = Math.max(approveEnd + gap, appSession.lastActivityMillis() + 5_000);
            execSession = signIn(c, executor, signInAt, signInStage);
            execAt = execSession.lastActivityMillis() + between(rnd, 3_000, 30_000);
        }
        long execEnd;
        if (scenario == Scenario.FAIL_PROVIDER_TIMEOUT_EXECUTE) {
            execEnd = executeProviderTimeout(c, flow, execSession, execAt);
            flow.status(executeStage, "FAILED");
            flow.failure(executeStage, abbreviate(BL_PROVIDER));
        } else {
            execEnd = execute(c, flow, execSession, execAt);
            flow.status(executeStage, "COMPLETED");
        }
        if (scenario == Scenario.DUPLICATE_SUBMIT) {
            duplicate.executor(executor);
            execEnd = execute(new Ctx(duplicate.flowRef(), rnd), duplicate, execSession, execEnd + between(rnd, 5_000, 60_000));
            duplicate.status(executeStage, "COMPLETED");
        }
        signOut(c, execSession, execEnd + between(rnd, 5_000, 120_000));
        return written;
    }

    /** The next 00:00-05:00 window (local business time) after {@code after}. */
    private long offHours(long after, SplittableRandom rnd) {
        java.time.ZonedDateTime local = java.time.Instant.ofEpochMilli(after).atZone(catalogue.zone());
        java.time.ZonedDateTime midnight = local.toLocalDate().plusDays(1).atStartOfDay(catalogue.zone());
        return midnight.toInstant().toEpochMilli() + between(rnd, 10 * 60_000, 4 * 3_600_000);
    }

    // ------------------------------------------------------------- incidents

    /** BRUTE_FORCE_LOGIN: 8-15 failed sign-ins for one user from one external IP within 5 minutes; returns when the success comes. */
    private long bruteForce(SplittableRandom rnd, User user, String ip, long t0, String relatedRef) {
        Ctx c = new Ctx(null, rnd);
        int attempts = 8 + rnd.nextInt(8);
        long t = t0;
        for (int i = 0; i < attempts; i++) {
            boolean mfa = rnd.nextInt(5) == 0;
            HttpCall call = new HttpCall(rnd, t, null, ip, "python-requests/2.31.0");
            long authAt = call.advance(rnd, 40, 400);
            Event.Builder b = mfa
                    ? auth(authAt, AUTH_LOGIN, Level.WARN, MFA_FAILED, "MFA code rejected")
                    : auth(authAt, AUTH_LOGIN, Level.WARN, LOGIN_FAILED, "Login failed").field("reason", "BAD_CREDENTIALS");
            emit(c, null, call, b.field("user", user.id()).field("rid", call.rid).field("ip", ip));
            long end = call.advance(rnd, 2, 20);
            emit(c, null, call, catalogue.gateway(end, catalogue.accessLine(ip, null, end, "POST", "/auth/login", 401, 64,
                    null, "python-requests/2.31.0", call.rid, call.elapsedSeconds())));
            t = end + between(rnd, 5_000, 250_000 / attempts);
        }
        incidents.accept(new Incident(Scenario.BRUTE_FORCE_LOGIN, user.id(), ip, t0, t, relatedRef, attempts * 2, "R07"));
        return t + between(rnd, 1_000, 5_000);
    }

    /** CONCURRENT_SESSIONS: a second session of the same user from another IP while the first is open. */
    private void concurrentSession(SplittableRandom rnd, User user, long t, String relatedRef) {
        Ctx c = new Ctx(null, rnd);
        String ip = "203.0.113." + (10 + rnd.nextInt(200));
        Session other = signIn(c, user, t, null, ip);
        HttpCall call = new HttpCall(rnd, other.lastActivityMillis() + between(rnd, 3_000, 30_000), other, ip, user.userAgent());
        emit(c, null, call, wfNoRef(call, call.advance(rnd, 5, 40), WF_QUERY, Level.INFO, null, "Listed " + rnd.nextInt(20) + " requests"));
        long end = gatewayEnd(c, null, call, "GET", "/api/requests/mine", 200, 1_200 + rnd.nextInt(2_000));
        long out = end + between(rnd, 30_000, 300_000);
        signOut(c, other, out);
        incidents.accept(new Incident(Scenario.CONCURRENT_SESSIONS, user.id(), ip, t, out, relatedRef, 7, "R08"));
    }

    /** AUTHZ_PROBING: a user without approve rights tries to approve or execute 5-20 requests within 10 minutes. */
    private void probing(SplittableRandom rnd, long t0, String relatedRef) {
        Ctx c = new Ctx(null, rnd);
        User prober = users.randomRequesterWithoutApproval(rnd);
        Session s = signIn(c, prober, t0, null);
        int probes = 5 + rnd.nextInt(16);
        List<String> targets = probeTargets.refs(t0 - 6 * 3_600_000L, probes, rnd);
        long t = s.lastActivityMillis() + between(rnd, 2_000, 20_000);
        long window = between(rnd, 120_000, 540_000);
        for (int i = 0; i < probes; i++) {
            String ref = targets.get(i % targets.size());
            boolean execute = rnd.nextInt(3) == 0;
            HttpCall call = new HttpCall(rnd, t, s, s.ip(), prober.userAgent());
            emit(c, null, call, auth(call.advance(rnd, 2, 15), AUTH_AUTHZ, Level.WARN, AUTHZ_DENIED, "Permission denied")
                    .field("user", prober.id()).field("rid", call.rid)
                    .field("perm", execute ? "requests:execute" : "requests:approve").field("resource", ref));
            t = gatewayEnd(c, null, call, "POST", "/api/requests/" + ref + (execute ? "/execute" : "/approve"), 403, 96)
                    + window / probes;
        }
        signOut(c, s, t + between(rnd, 5_000, 60_000));
        incidents.accept(new Incident(Scenario.AUTHZ_PROBING, prober.id(), s.ip(), t0, t, relatedRef, probes * 2 + 4, "R14"));
    }

    private static final List<String> SCANNER_PATHS = List.of("/.env", "/wp-login.php", "/admin", "/api/v1/../../etc/passwd",
            "/.git/config", "/phpmyadmin/", "/api/users", "/server-status", "/backup.zip", "/actuator/env", "/api/requests",
            "/cgi-bin/test.cgi", "/config.json", "/login.action", "/.aws/credentials");
    private static final List<String> SCANNER_AGENTS = List.of("sqlmap/1.7", "python-requests/2.31.0", "Nikto/2.5.0", "masscan/1.3");

    /** SCANNER_TRAFFIC: one external IP sends 100-400 requests in 5 minutes to unknown paths, mostly 401/403/404. */
    private void scanner(SplittableRandom rnd, long t0, String relatedRef) {
        Ctx c = new Ctx(null, rnd);
        String ip = "203.0.113." + (10 + rnd.nextInt(200));
        String agent = SCANNER_AGENTS.get(rnd.nextInt(SCANNER_AGENTS.size()));
        int requests = 100 + rnd.nextInt(301);
        long span = between(rnd, 60_000, 290_000);
        long t = t0;
        for (int i = 0; i < requests; i++) {
            String path = SCANNER_PATHS.get(rnd.nextInt(SCANNER_PATHS.size()));
            int r = rnd.nextInt(100);
            int status = r < 70 ? 404 : r < 85 ? 401 : r < 95 ? 403 : 200;
            HttpCall call = new HttpCall(rnd, t, null, ip, agent);
            long end = call.advance(rnd, 1, 30);
            emit(c, null, call, catalogue.gateway(end, catalogue.accessLine(ip, null, end, rnd.nextInt(4) == 0 ? "POST" : "GET",
                    path, status, status == 200 ? 512 : 64, null, agent, call.rid, call.elapsedSeconds())));
            t += span / requests;
        }
        incidents.accept(new Incident(Scenario.SCANNER_TRAFFIC, null, ip, t0, t, relatedRef, requests, "R15"));
    }

    // ------------------------------------------------------------- sessions

    /** Gateway {@code POST /auth/login} plus auth {@code LOGIN_OK}; sign-in takes 0.5 to 2 s. */
    public Session signIn(Ctx c, User user, long t, String stage) {
        return signIn(c, user, t, stage, user.homeIp());
    }

    /** Sign-in from a given client IP (an external address for brute force or a second device). */
    public Session signIn(Ctx c, User user, long t, String stage, String ip) {
        SplittableRandom rnd = c.rnd();
        Session session = new Session(Ids.sessionId(rnd), user, ip, t);
        HttpCall call = new HttpCall(rnd, t, null, ip, user.userAgent());
        long total = between(rnd, 500, 2_000);
        long authAt = t + (long) (total * 0.6);
        emit(c, stage, call, auth(authAt, AUTH_LOGIN, Level.INFO, LOGIN_OK, "Login successful")
                .field("user", user.id()).field("session", session.id()).field("rid", call.rid)
                .field("ip", session.ip()).field("mfa", "totp"));
        long end = t + total;
        emit(c, stage, call, catalogue.gateway(end, catalogue.accessLine(session.ip(), null, end, "POST", "/auth/login",
                200, 312 + rnd.nextInt(40), REFERRER, user.userAgent(), call.rid, total / 1000.0)));
        session.touch(end);
        return session;
    }

    /** Gateway {@code POST /auth/logout} plus auth {@code LOGOUT}. */
    public void signOut(Ctx c, Session session, long t) {
        SplittableRandom rnd = c.rnd();
        HttpCall call = new HttpCall(rnd, t, session, session.ip(), session.user().userAgent());
        long authAt = call.advance(rnd, 8, 60);
        emit(c, SIGN_OUT, call, auth(authAt, AUTH_SESSION, Level.INFO, LOGOUT, "Logged out")
                .field("user", session.user().id()).field("session", session.id()).field("rid", call.rid));
        long end = call.advance(rnd, 3, 30);
        emit(c, SIGN_OUT, call, catalogue.gateway(end, catalogue.accessLine(session.ip(), session.user().id(), end,
                "POST", "/auth/logout", 204, 0, REFERRER, session.user().userAgent(), call.rid, call.elapsedSeconds())));
        session.touch(end);
    }

    /** Auth {@code SESSION_EXPIRED} after {@link #SESSION_IDLE_MINUTES} without activity; no HTTP call. */
    public void expire(Ctx c, Session session) {
        long at = session.lastActivityMillis() + SESSION_IDLE_MINUTES * 60_000L + c.rnd().nextInt(1_000);
        emit(c, SIGN_OUT, null, auth(at, AUTH_SESSION, Level.INFO, SESSION_EXPIRED, "Session expired")
                .field("user", session.user().id()).field("session", session.id())
                .field("idleMinutes", String.valueOf(SESSION_IDLE_MINUTES)));
    }

    // --------------------------------------------------------------- stages

    /** Create, part 1: the form opens and the workflow service assigns the draft flowRef. */
    long openForm(Ctx c, Session s, long t) {
        SplittableRandom rnd = c.rnd();
        String stage = definition.createStage();
        HttpCall call = new HttpCall(rnd, t, s, s.ip(), s.user().userAgent());
        // The draft flowRef does not exist yet on the controller's first line; it links by request ID.
        emit(c, stage, call, wfNoRef(call, call.advance(rnd, 3, 15), WF_CONTROLLER, Level.DEBUG, null,
                "GET /api/requests/new"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 40), WF_REQUEST, Level.INFO, FORM_OPENED,
                "Request form opened"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 60), WF_VENDOR_CACHE, Level.DEBUG, null,
                "Loaded " + (20 + rnd.nextInt(80)) + " vendors for the picker"));
        return gatewayEnd(c, stage, call, "GET", "/api/requests/new", 200, 1_800 + rnd.nextInt(400));
    }

    /** Create, part 2: submit, validation, {@code request.created}, budget reservation in billing. */
    long submit(Ctx c, Flow flow, Session s, long t) {
        SplittableRandom rnd = c.rnd();
        String stage = definition.createStage();
        HttpCall call = new HttpCall(rnd, t, s, s.ip(), s.user().userAgent());
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 15), WF_CONTROLLER, Level.DEBUG, null,
                "POST /api/requests"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 30), WF_VALIDATOR, Level.INFO, null,
                "Validating request fields"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 40), WF_REQUEST, Level.INFO, CREATED, "Request created")
                .field("amount", flow.amount()).field("currency", flow.currency())
                .field("vendor_account", flow.vendorAccount()).field("cost_centre", flow.costCentre()));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 2, 10), WF_BILLING_CLIENT, Level.INFO, null,
                "Reserving budget on " + flow.costCentre()));
        billingLine(c, stage, call, call.advance(rnd, 2, 20), BL_POOL, "borrow", 77, Level.DEBUG,
                "Acquired connection from pool", " for %s", false);
        billingLine(c, stage, call, call.advance(rnd, 5, 40), BL_BUDGET_DAO, "load", 61, Level.DEBUG,
                "Loading budget for cost centre " + flow.costCentre(), " (%s)", true);
        billingLine(c, stage, call, call.advance(rnd, 10, 150), BL_BUDGET_SERVICE, "reserve", 88, Level.INFO,
                PREFIX_BUDGET_RESERVED + flow.amount().toPlainString() + " " + flow.currency() + " on cost centre "
                        + flow.costCentre(), " for %s", true);
        billingLine(c, stage, call, call.advance(rnd, 5, 80), BL_BUDGET_DAO, "commit", 104, Level.DEBUG,
                "Budget transaction committed", " ref=%s", true);
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 20), WF_REQUEST, Level.DEBUG, null,
                "Request persisted with status PENDING_APPROVAL"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 30), WF_REQUEST, Level.INFO, null,
                "Request submitted for approval"));
        return gatewayEnd(c, stage, call, "POST", "/api/requests", 201, 412 + rnd.nextInt(60));
    }

    /** Approve: permission check, {@code request.approval_started}, limit check in billing, {@code request.approved}. */
    long approve(Ctx c, Flow flow, Session s, long t) {
        return approve(c, flow, s, t, 0);
    }

    /** SLOW_APPROVE: the billing limit lookup takes 20 to 40 s but succeeds (R09). */
    long approveSlowly(Ctx c, Flow flow, Session s, long t) {
        return approve(c, flow, s, t, between(c.rnd(), 20_000, 40_000));
    }

    private long approve(Ctx c, Flow flow, Session s, long t, long slowMillis) {
        SplittableRandom rnd = c.rnd();
        String stage = definition.approveStage();
        HttpCall call = new HttpCall(rnd, t, s, s.ip(), s.user().userAgent());
        String user = s.user().id();
        emit(c, stage, call, auth(call.advance(rnd, 2, 15), AUTH_AUTHZ, Level.INFO, AUTHZ_OK, "Permission granted")
                .field("user", user).field("rid", call.rid).field("perm", "requests:approve")
                .field("resource", flow.flowRef()));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 20), WF_REQUEST, Level.DEBUG, null,
                "Loaded request with status PENDING_APPROVAL"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 20), WF_APPROVAL, Level.INFO, APPROVAL_STARTED,
                "Approval started"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 2, 10), WF_BILLING_CLIENT, Level.INFO, null,
                "Checking approval limit for " + user));
        billingLine(c, stage, call, call.advance(rnd, 2, 20), BL_POOL, "borrow", 77, Level.DEBUG,
                "Acquired connection from pool", " for %s", false);
        billingLine(c, stage, call, call.advance(rnd, 5, 40), BL_LIMIT_DAO, "find", 57, Level.DEBUG,
                "Loading approval limit for " + user, " request=%s", true);
        if (slowMillis > 0) {
            call.advance(rnd, (int) slowMillis, (int) slowMillis + 500);
            billingLine(c, stage, call, call.cursor(), BL_LIMIT_DAO, "find", 61, Level.WARN,
                    "Slow query: approval limit lookup took " + slowMillis + " ms", " request=%s", false);
        }
        billingLine(c, stage, call, call.advance(rnd, 10, 200), BL_LIMIT_CHECK, "check", 142, Level.INFO,
                PREFIX_LIMIT_CHECKED + "amount=" + flow.amount().toPlainString() + " limit="
                        + s.user().approvalLimit().toPlainString() + " approver=" + user, " request=%s", false);
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 40), WF_APPROVAL, Level.INFO, APPROVED, "Request approved"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 2, 15), WF_REQUEST, Level.DEBUG, null,
                "Request status changed to APPROVED"));
        return gatewayEnd(c, stage, call, "POST", "/api/requests/" + flow.flowRef() + "/approve", 200, 380 + rnd.nextInt(60));
    }

    /** Execute: payout via the external {@code PaymentProvider} (XML over HTTPS), then a {@code billing.events} publish. */
    long execute(Ctx c, Flow flow, Session s, long t) {
        SplittableRandom rnd = c.rnd();
        String stage = definition.executeStage();
        HttpCall call = new HttpCall(rnd, t, s, s.ip(), s.user().userAgent());
        String corr = Ids.externalCorrelationId(rnd);
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 20), WF_EXECUTION, Level.INFO, EXECUTION_STARTED,
                "Execution started"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 2, 10), WF_BILLING_CLIENT, Level.INFO, null,
                "Submitting payout"));
        billingLine(c, stage, call, call.advance(rnd, 5, 40), BL_PAYOUT, "execute", 203, Level.INFO,
                "Starting payout of " + flow.amount().toPlainString() + " " + flow.currency(), " for %s", true);
        billingLine(c, stage, call, call.advance(rnd, 5, 40), BL_PAYOUT, "persist", 221, Level.DEBUG,
                "Payout row inserted with status SUBMITTED", " for %s", true);
        boolean ref = rnd.nextDouble() < BILLING_FLOW_REF_RATE;
        billingRaw(c, stage, call, call.advance(rnd, 5, 60), BL_PROVIDER, "submit", 96, Level.INFO,
                PREFIX_PROVIDER_CALL + "PaymentProvider.submit externalCorrelationId=" + corr + "\n"
                        + Multiline.payoutRequestXml(corr, flow.amount(), flow.currency(), flow.vendorAccount(),
                        ref ? flow.flowRef() : null));
        boolean refInResponse = rnd.nextDouble() < BILLING_FLOW_REF_RATE;
        billingRaw(c, stage, call, call.advance(rnd, 150, 450), BL_PROVIDER, "submit", 118, Level.INFO,
                PREFIX_PROVIDER_RESPONSE + "externalCorrelationId=" + corr + " status=200\n"
                        + Multiline.payoutResponseXml(corr, 200, "ACCEPTED", refInResponse ? flow.flowRef() : null));
        billingLine(c, stage, call, call.advance(rnd, 5, 60), BL_PUBLISHER, "publish", 45, Level.INFO,
                PREFIX_PUBLISHED + "externalCorrelationId=" + corr, " ref=%s", false);
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 40), WF_EXECUTION, Level.INFO, EXECUTION_COMPLETED,
                "Execution completed"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 2, 15), WF_REQUEST, Level.DEBUG, null,
                "Request status changed to EXECUTED"));
        return gatewayEnd(c, stage, call, "POST", "/api/requests/" + flow.flowRef() + "/execute", 200, 290 + rnd.nextInt(60));
    }

    /** FAIL_DB_DOWN_CREATE: the workflow service cannot reach its database; the gateway returns 503. */
    long submitDatabaseDown(Ctx c, Session s, long t) {
        SplittableRandom rnd = c.rnd();
        String stage = definition.createStage();
        HttpCall call = new HttpCall(rnd, t, s, s.ip(), s.user().userAgent());
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 15), WF_CONTROLLER, Level.DEBUG, null, "POST /api/requests"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 30), WF_VALIDATOR, Level.INFO, null, "Validating request fields"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 1_000, 3_000), WF_REQUEST, Level.ERROR, null,
                "Could not persist request")
                .field("error.type", "java.net.ConnectException")
                .field("error.message", "Connection refused")
                .field("error.stack_trace", Multiline.jsonStackTrace(rnd, "java.net.ConnectException", "Connection refused",
                        "com.acme.workflow.db.DataSourceRouter.getConnection(DataSourceRouter.java:71)")));
        return gatewayEnd(c, stage, call, "POST", "/api/requests", 503, 96);
    }

    /** FAIL_VALIDATION_CREATE: invalid vendor account; WARN {@code request.validation_failed}, gateway 422. */
    long submitInvalid(Ctx c, Session s, long t) {
        SplittableRandom rnd = c.rnd();
        String stage = definition.createStage();
        HttpCall call = new HttpCall(rnd, t, s, s.ip(), s.user().userAgent());
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 15), WF_CONTROLLER, Level.DEBUG, null, "POST /api/requests"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 30), WF_VALIDATOR, Level.WARN, VALIDATION_FAILED,
                "Request validation failed").field("error.message", "vendor_account invalid"));
        return gatewayEnd(c, stage, call, "POST", "/api/requests", 422, 148);
    }

    /** FAIL_DB_TIMEOUT_APPROVE: the billing limit lookup times out after 30 s; the gateway returns 500. */
    long approveDatabaseTimeout(Ctx c, Flow flow, Session s, long t) {
        SplittableRandom rnd = c.rnd();
        String stage = definition.approveStage();
        HttpCall call = new HttpCall(rnd, t, s, s.ip(), s.user().userAgent());
        String user = s.user().id();
        emit(c, stage, call, auth(call.advance(rnd, 2, 15), AUTH_AUTHZ, Level.INFO, AUTHZ_OK, "Permission granted")
                .field("user", user).field("rid", call.rid).field("perm", "requests:approve")
                .field("resource", flow.flowRef()));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 20), WF_REQUEST, Level.DEBUG, null,
                "Loaded request with status PENDING_APPROVAL"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 20), WF_APPROVAL, Level.INFO, APPROVAL_STARTED, "Approval started"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 2, 10), WF_BILLING_CLIENT, Level.INFO, null,
                "Checking approval limit for " + user));
        billingLine(c, stage, call, call.advance(rnd, 2, 20), BL_POOL, "borrow", 77, Level.DEBUG,
                "Acquired connection from pool", " for %s", false);
        billingLine(c, stage, call, call.advance(rnd, 5, 40), BL_LIMIT_DAO, "find", 57, Level.DEBUG,
                "Loading approval limit for " + user + "\n" + Multiline.sql(rnd), " request=%s", false);
        billingLine(c, stage, call, call.advance(rnd, 30_000, 30_050), BL_LIMIT_CHECK, "check", 142, Level.ERROR,
                "Limit lookup failed\n" + Multiline.stackTrace(rnd, "java.sql.SQLTimeoutException",
                        "Query timed out after 30000 ms", "com.acme.billing.limits.LimitDao.find(LimitDao.java:57)"),
                " request=%s", false);
        billingLine(c, stage, call, call.advance(rnd, 2, 30), BL_LIMIT_DAO, "rollback", 63, Level.DEBUG,
                "Transaction rolled back", " request=%s", false);
        billingLine(c, stage, call, call.advance(rnd, 1, 20), BL_POOL, "release", 91, Level.DEBUG,
                "Returned connection to pool", " for %s", false);
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 40), WF_APPROVAL, Level.ERROR, null, "Limit check failed")
                .field("error.type", "com.acme.workflow.client.BillingClientException")
                .field("error.message", "billing-service returned 500"));
        return gatewayEnd(c, stage, call, "POST", "/api/requests/" + flow.flowRef() + "/approve", 500, 128);
    }

    /** APPROVER_REJECT: a normal business outcome; {@code request.rejected} with reason INSUFFICIENT_DOCS. */
    long reject(Ctx c, Flow flow, Session s, long t) {
        SplittableRandom rnd = c.rnd();
        String stage = definition.approveStage();
        HttpCall call = new HttpCall(rnd, t, s, s.ip(), s.user().userAgent());
        emit(c, stage, call, auth(call.advance(rnd, 2, 15), AUTH_AUTHZ, Level.INFO, AUTHZ_OK, "Permission granted")
                .field("user", s.user().id()).field("rid", call.rid).field("perm", "requests:approve")
                .field("resource", flow.flowRef()));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 20), WF_REQUEST, Level.DEBUG, null,
                "Loaded request with status PENDING_APPROVAL"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 20), WF_APPROVAL, Level.INFO, APPROVAL_STARTED, "Approval started"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 40), WF_APPROVAL, Level.INFO, REJECTED, "Request rejected")
                .field("reason", "INSUFFICIENT_DOCS"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 2, 15), WF_REQUEST, Level.DEBUG, null,
                "Request status changed to REJECTED"));
        return gatewayEnd(c, stage, call, "POST", "/api/requests/" + flow.flowRef() + "/reject", 200, 371 + rnd.nextInt(40));
    }

    /** FAIL_PROVIDER_TIMEOUT_EXECUTE: the provider does not answer within 30 s; status 504; {@code execution.failed}. */
    long executeProviderTimeout(Ctx c, Flow flow, Session s, long t) {
        SplittableRandom rnd = c.rnd();
        String stage = definition.executeStage();
        HttpCall call = new HttpCall(rnd, t, s, s.ip(), s.user().userAgent());
        String corr = Ids.externalCorrelationId(rnd);
        emit(c, stage, call, wf(c, call, call.advance(rnd, 3, 20), WF_EXECUTION, Level.INFO, EXECUTION_STARTED, "Execution started"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 2, 10), WF_BILLING_CLIENT, Level.INFO, null, "Submitting payout"));
        billingLine(c, stage, call, call.advance(rnd, 5, 40), BL_PAYOUT, "execute", 203, Level.INFO,
                "Starting payout of " + flow.amount().toPlainString() + " " + flow.currency(), " for %s", true);
        billingLine(c, stage, call, call.advance(rnd, 5, 40), BL_PAYOUT, "persist", 221, Level.DEBUG,
                "Payout row inserted with status SUBMITTED", " for %s", true);
        boolean ref = rnd.nextDouble() < BILLING_FLOW_REF_RATE;
        billingRaw(c, stage, call, call.advance(rnd, 5, 60), BL_PROVIDER, "submit", 96, Level.INFO,
                PREFIX_PROVIDER_CALL + "PaymentProvider.submit externalCorrelationId=" + corr + "\n"
                        + Multiline.payoutRequestXml(corr, flow.amount(), flow.currency(), flow.vendorAccount(),
                        ref ? flow.flowRef() : null));
        billingLine(c, stage, call, call.advance(rnd, 30_000, 30_100), BL_PROVIDER, "submit", 131, Level.ERROR,
                "Provider call failed externalCorrelationId=" + corr + "\n" + Multiline.stackTrace(rnd,
                        "java.net.SocketTimeoutException", "Read timed out",
                        "com.acme.billing.integration.PaymentProviderClient.submit(PaymentProviderClient.java:96)"),
                " for %s", false);
        billingLine(c, stage, call, call.advance(rnd, 2, 20), BL_PROVIDER, "submit", 118, Level.WARN,
                PREFIX_PROVIDER_RESPONSE + "externalCorrelationId=" + corr + " status=504", " ref=%s", false);
        billingLine(c, stage, call, call.advance(rnd, 2, 30), BL_PAYOUT, "markTimedOut", 240, Level.WARN,
                "Payout marked TIMED_OUT externalCorrelationId=" + corr, " for %s", false);
        emit(c, stage, call, wf(c, call, call.advance(rnd, 5, 40), WF_EXECUTION, Level.ERROR, EXECUTION_FAILED, "Execution failed")
                .field("error.type", "com.acme.workflow.client.BillingClientException")
                .field("error.message", "payment provider timed out (504)"));
        emit(c, stage, call, wf(c, call, call.advance(rnd, 2, 15), WF_REQUEST, Level.DEBUG, null,
                "Request status changed to EXECUTION_FAILED"));
        return gatewayEnd(c, stage, call, "POST", "/api/requests/" + flow.flowRef() + "/execute", 502, 141);
    }

    // -------------------------------------------------------------- helpers

    /** Format B line for a call: request ID always, user and session on user calls, the flowRef from the form onwards. */
    Event.Builder wf(Ctx c, HttpCall call, long ts, String logger, Level level, String action, String message) {
        Event.Builder b = workflow(ts, call.workflowHost, call.httpThread, logger, level, action, message)
                .field("http.request.id", call.rid);
        if (call.session != null) {
            b.field("user.id", call.session.user().id()).field("session.id", call.session.id());
        }
        return b.field("flow.ref", c.flowRef());
    }

    /** Format B line without a flowRef (before the draft reference exists, or outside any flow). */
    Event.Builder wfNoRef(HttpCall call, long ts, String logger, Level level, String action, String message) {
        Event.Builder b = workflow(ts, call.workflowHost, call.httpThread, logger, level, action, message)
                .field("http.request.id", call.rid);
        if (call.session != null) {
            b.field("user.id", call.session.user().id()).field("session.id", call.session.id());
        }
        return b;
    }

    /**
     * Format D line on the call's worker thread. The request ID is in the MDC at
     * {@link #BILLING_REQUEST_ID_RATE}; the flowRef is appended with {@code refFormat}
     * at {@link #BILLING_FLOW_REF_RATE}; an SQL statement follows at {@link #SQL_RATE}.
     */
    void billingLine(Ctx c, String stage, HttpCall call, long ts, String logger, String method, int line, Level level,
                     String message, String refFormat, boolean sqlAllowed) {
        SplittableRandom rnd = c.rnd();
        String text = message;
        if (c.flowRef() != null && rnd.nextDouble() < BILLING_FLOW_REF_RATE) {
            // On the first line, before any continuation (stack trace, SQL).
            int nl = text.indexOf('\n');
            String suffix = String.format(refFormat, c.flowRef());
            text = nl < 0 ? text + suffix : text.substring(0, nl) + suffix + text.substring(nl);
        }
        if (sqlAllowed && rnd.nextDouble() < SQL_RATE) {
            text += "\n" + Multiline.sql(rnd);
        }
        billingRaw(c, stage, call, ts, logger, method, line, level, text);
    }

    /** Format D line with the message as given (the caller decides about the flowRef). */
    void billingRaw(Ctx c, String stage, HttpCall call, long ts, String logger, String method, int line, Level level,
                    String message) {
        Event.Builder b = billing(ts, call.billingWorker, logger, method, line, level, message);
        if (c.rnd().nextDouble() < BILLING_REQUEST_ID_RATE) {
            b.mdcRequestId(call.rid);
        }
        emit(c, stage, call, b);
    }

    /** The gateway writes its line when the call completes. */
    long gatewayEnd(Ctx c, String stage, HttpCall call, String method, String path, int status, int bytes) {
        long end = call.advance(c.rnd(), 2, 12);
        String user = call.session != null ? call.session.user().id() : null;
        emit(c, stage, call, catalogue.gateway(end, catalogue.accessLine(call.clientIp, user, end, method, path, status,
                bytes, REFERRER, call.userAgent, call.rid, call.elapsedSeconds())));
        if (call.session != null) {
            call.session.touch(end);
        }
        return end;
    }

    void emit(Ctx c, String stage, HttpCall call, Event.Builder b) {
        out.accept(b.truth(c.flowRef(), call != null ? call.rid : null, stage));
    }

    static long between(SplittableRandom rnd, long minInclusive, long maxInclusive) {
        return minInclusive + (long) (rnd.nextDouble() * (maxInclusive - minInclusive + 1));
    }

    public Ctx ctx(String flowRef, SplittableRandom rnd) {
        return new Ctx(flowRef, rnd);
    }

    public MessageCatalogue catalogue() {
        return catalogue;
    }
}
