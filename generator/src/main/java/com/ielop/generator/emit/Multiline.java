package com.ielop.generator.emit;

import java.math.BigDecimal;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Multi-line content for the legacy billing service (format D, spec sections 3
 * and 5.4): stack traces, SQL statements and the payment provider's XML bodies.
 * Continuation lines never start with a date, so Filebeat's multiline pattern
 * keeps them with their event.
 */
public final class Multiline {

    private static final List<String> SQL = List.of(
            "SELECT remaining_amount, currency\n  FROM budget\n WHERE cost_centre = ? AND fiscal_year = ?",
            "UPDATE budget\n   SET reserved_amount = reserved_amount + ?\n WHERE cost_centre = ?",
            "SELECT limit_amount, currency\n  FROM approver_limit\n WHERE user_id = ?",
            "INSERT INTO payout (correlation_id, amount, currency, status)\nVALUES (?, ?, ?, 'SUBMITTED')",
            "SELECT id, status FROM payout WHERE correlation_id = ?",
            "SELECT cost_centre, SUM(reserved_amount)\n  FROM budget_line\n GROUP BY cost_centre");

    private static final List<String> FRAMES_BILLING = List.of(
            "com.acme.billing.limits.LimitDao.find(LimitDao.java:57)",
            "com.acme.billing.limits.LimitCheck.check(LimitCheck.java:142)",
            "com.acme.billing.budget.BudgetDao.reserve(BudgetDao.java:88)",
            "com.acme.billing.payout.PayoutService.execute(PayoutService.java:203)",
            "com.acme.billing.integration.PaymentProviderClient.submit(PaymentProviderClient.java:96)",
            "com.acme.billing.db.JdbcTemplate.query(JdbcTemplate.java:412)",
            "com.acme.billing.db.ConnectionPool.borrow(ConnectionPool.java:77)",
            "com.acme.billing.web.BillingEndpoint.handle(BillingEndpoint.java:64)",
            "com.acme.billing.worker.WorkerLoop.run(WorkerLoop.java:39)");
    private static final List<String> FRAMES_RUNTIME = List.of(
            "java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)",
            "java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)",
            "java.base/java.lang.Thread.run(Thread.java:1583)");

    private Multiline() {
    }

    public static String sql(SplittableRandom rnd) {
        return SQL.get(rnd.nextInt(SQL.size()));
    }

    /** {@code type: message} followed by 5 to 40 {@code \tat} frames. */
    public static String stackTrace(SplittableRandom rnd, String exceptionType, String message, String firstFrame) {
        int frames = 5 + rnd.nextInt(36);
        StringBuilder sb = new StringBuilder(exceptionType).append(": ").append(message);
        sb.append("\n\tat ").append(firstFrame);
        for (int i = 1; i < frames - FRAMES_RUNTIME.size(); i++) {
            sb.append("\n\tat ").append(FRAMES_BILLING.get(rnd.nextInt(FRAMES_BILLING.size())));
        }
        for (String frame : FRAMES_RUNTIME) {
            sb.append("\n\tat ").append(frame);
        }
        return sb.toString();
    }

    /** One-string stack trace for the JSON {@code error.stack_trace} field of format B. */
    public static String jsonStackTrace(SplittableRandom rnd, String exceptionType, String message, String firstFrame) {
        int frames = 5 + rnd.nextInt(10);
        StringBuilder sb = new StringBuilder(exceptionType).append(": ").append(message);
        sb.append("\n\tat ").append(firstFrame);
        for (int i = 1; i < frames; i++) {
            sb.append("\n\tat com.acme.workflow.").append(List.of(
                    "request.RequestService.submit(RequestService.java:118)",
                    "request.RequestRepository.save(RequestRepository.java:44)",
                    "db.DataSourceRouter.getConnection(DataSourceRouter.java:71)",
                    "web.RequestController.create(RequestController.java:52)",
                    "web.ErrorFilter.doFilter(ErrorFilter.java:30)").get(rnd.nextInt(5)));
        }
        return sb.toString();
    }

    public static String payoutRequestXml(String correlationId, BigDecimal amount, String currency, String account,
                                          String flowRefOrNull) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<PayoutRequest xmlns=\"urn:acme:payout:v1\">\n"
                + "  <CorrelationId>" + correlationId + "</CorrelationId>\n"
                + "  <Amount currency=\"" + currency + "\">" + amount.toPlainString() + "</Amount>\n"
                + "  <BeneficiaryAccount>" + account + "</BeneficiaryAccount>\n"
                + (flowRefOrNull != null ? "  <Reference>" + flowRefOrNull + "</Reference>\n" : "")
                + "</PayoutRequest>";
    }

    public static String payoutResponseXml(String correlationId, int status, String providerStatus, String flowRefOrNull) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<PayoutResponse xmlns=\"urn:acme:payout:v1\">\n"
                + "  <CorrelationId>" + correlationId + "</CorrelationId>\n"
                + (flowRefOrNull != null ? "  <Reference>" + flowRefOrNull + "</Reference>\n" : "")
                + "  <HttpStatus>" + status + "</HttpStatus>\n"
                + "  <Status>" + providerStatus + "</Status>\n"
                + "</PayoutResponse>";
    }
}
