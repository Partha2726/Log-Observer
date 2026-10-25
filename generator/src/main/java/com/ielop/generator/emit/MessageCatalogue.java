package com.ielop.generator.emit;

import com.ielop.generator.model.Service;
import org.apache.logging.log4j.Level;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The frozen event catalogue (spec section 5.7): event actions, the format D
 * message prefixes that Logstash maps to actions, logger names, and the
 * builders that put them into each format. Filler lines vary freely; the
 * actions and prefixes here do not.
 */
public final class MessageCatalogue {

    // Format C (syslog MSGID)
    public static final String LOGIN_OK = "LOGIN_OK";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String MFA_FAILED = "MFA_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String SESSION_EXPIRED = "SESSION_EXPIRED";
    public static final String AUTHZ_OK = "AUTHZ_OK";
    public static final String AUTHZ_DENIED = "AUTHZ_DENIED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String ROLE_CHANGED = "ROLE_CHANGED";

    // Format B (event.action)
    public static final String FORM_OPENED = "request.form_opened";
    public static final String VALIDATION_FAILED = "request.validation_failed";
    public static final String CREATED = "request.created";
    public static final String APPROVAL_STARTED = "request.approval_started";
    public static final String APPROVED = "request.approved";
    public static final String REJECTED = "request.rejected";
    public static final String EXECUTION_STARTED = "execution.started";
    public static final String EXECUTION_COMPLETED = "execution.completed";
    public static final String EXECUTION_FAILED = "execution.failed";

    // Format D message prefixes (mapped to event.action by deploy/logstash/patterns/flow)
    public static final String PREFIX_BUDGET_RESERVED = "Reserved ";
    public static final String PREFIX_LIMIT_CHECKED = "Limit check passed ";
    public static final String PREFIX_PROVIDER_CALL = "Provider call ";
    public static final String PREFIX_PROVIDER_RESPONSE = "Provider response ";
    public static final String PREFIX_PUBLISHED = "Published to billing.events ";

    // Loggers (fictional com.acme.* only)
    public static final String WF_CONTROLLER = "com.acme.workflow.web.RequestController";
    public static final String WF_REQUEST = "com.acme.workflow.request.RequestService";
    public static final String WF_VALIDATOR = "com.acme.workflow.request.RequestValidator";
    public static final String WF_QUERY = "com.acme.workflow.request.RequestQueryService";
    public static final String WF_APPROVAL = "com.acme.workflow.approval.ApprovalService";
    public static final String WF_EXECUTION = "com.acme.workflow.execution.ExecutionService";
    public static final String WF_BILLING_CLIENT = "com.acme.workflow.client.BillingClient";
    public static final String WF_SYNC_JOB = "com.acme.workflow.jobs.BudgetSyncJob";
    public static final String WF_REMINDER_JOB = "com.acme.workflow.jobs.ReminderJob";
    public static final String WF_VENDOR_CACHE = "com.acme.workflow.cache.VendorCache";

    public static final String BL_BUDGET_DAO = "com.acme.billing.budget.BudgetDao";
    public static final String BL_BUDGET_SERVICE = "com.acme.billing.budget.BudgetService";
    public static final String BL_LIMIT_DAO = "com.acme.billing.limits.LimitDao";
    public static final String BL_LIMIT_CHECK = "com.acme.billing.limits.LimitCheck";
    public static final String BL_PAYOUT = "com.acme.billing.payout.PayoutService";
    public static final String BL_PROVIDER = "com.acme.billing.integration.PaymentProviderClient";
    public static final String BL_PUBLISHER = "com.acme.billing.integration.EventPublisher";
    public static final String BL_SYNC_JOB = "com.acme.billing.jobs.BudgetSyncJob";
    public static final String BL_POOL = "com.acme.billing.db.ConnectionPool";
    public static final String BL_RATE_CACHE = "com.acme.billing.cache.RateCache";

    public static final String AUTH_LOGIN = "com.acme.auth.LoginService";
    public static final String AUTH_SESSION = "com.acme.auth.SessionService";
    public static final String AUTH_AUTHZ = "com.acme.auth.PermissionService";
    public static final String AUTH_ACCOUNT = "com.acme.auth.AccountService";
    public static final String AUTH_CLEANUP = "com.acme.auth.TokenCleanupJob";

    public static final String GATEWAY_HOST = "gw-01";
    public static final String AUTH_HOST = "auth-01";
    public static final String BILLING_HOST = "bill-01";
    public static final String REFERRER = "https://app.acme.example/requests";

    private static final DateTimeFormatter TIME_LOCAL = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    private final ZoneId gatewayZone;

    /** How format D's {@code %c{1.}} prints a logger: every package abbreviated to its first letter. */
    public static String abbreviate(String logger) {
        String[] parts = logger.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            sb.append(parts[i].charAt(0)).append('.');
        }
        return sb.append(parts[parts.length - 1]).toString();
    }

    public MessageCatalogue(ZoneId gatewayZone) {
        this.gatewayZone = gatewayZone;
    }

    /** The company's local zone (gateway and legacy service), also its business hours. */
    public ZoneId zone() {
        return gatewayZone;
    }

    /** Format A: {@code $remote_addr - $remote_user [$time_local] "$request" $status $body_bytes_sent "$http_referer" "$http_user_agent" rid= rt=}. */
    public String accessLine(String ip, String user, long ts, String method, String path, int status, int bytes,
                             String referrer, String userAgent, String rid, double seconds) {
        String time = TIME_LOCAL.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), gatewayZone));
        return ip + " - " + (user == null ? "-" : user) + " [" + time + "] \"" + method + " " + path + " HTTP/1.1\" "
                + status + " " + bytes + " \"" + (referrer == null ? "-" : referrer) + "\" \"" + userAgent + "\" rid=" + rid
                + " rt=" + String.format(Locale.ROOT, "%.3f", seconds);
    }

    public Event.Builder gateway(long ts, String line) {
        return Event.at(ts, Service.GATEWAY).host(GATEWAY_HOST).message(line).level(Level.INFO);
    }

    public static Event.Builder auth(long ts, String logger, Level level, String action, String message) {
        return Event.at(ts, Service.AUTH).host(AUTH_HOST).thread("main").logger(logger).level(level)
                .action(action).message(message);
    }

    public static Event.Builder workflow(long ts, String host, String thread, String logger, Level level,
                                         String action, String message) {
        return Event.at(ts, Service.WORKFLOW).host(host).thread(thread).logger(logger).level(level)
                .action(action).message(message);
    }

    public static Event.Builder billing(long ts, String thread, String logger, String method, int line, Level level,
                                        String message) {
        return Event.at(ts, Service.BILLING).host(BILLING_HOST).thread(thread).logger(logger)
                .location(logger, method, line).level(level).message(message);
    }
}
