package com.ielop.platform.assistant;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Definitions of the anomaly rules for {@code explain_rule} (section 10), worded by position in the
 * flow definition (first role, approval stage, final stage) so they fit any flow.
 */
public final class RuleCatalogue {

    private RuleCatalogue() {
    }

    public static final Map<String, String> RULES = new LinkedHashMap<>();

    static {
        RULES.put("R01", "Self-approval (CRITICAL): the user in the first role (who created the request) is also the user in the second role, and the approval stage completed.");
        RULES.put("R02", "Approver is executor (MEDIUM): the user in the second-to-last role also holds the last role. Disabled by default; whether this is allowed is company policy.");
        RULES.put("R03", "Execution without approval (CRITICAL): the final stage was reached while the approval stage is NOT_REACHED, FAILED or REJECTED.");
        RULES.put("R04", "Stage order violation (HIGH): stage start times are not in flow-definition order.");
        RULES.put("R05", "Stage failed (HIGH for the approval and final stages, MEDIUM otherwise): a stage ended FAILED; the finding cites the line that made it fail.");
        RULES.put("R06", "Stuck flow (MEDIUM): the creation stage completed, but there was no approval and no final stage within stuckAfterMinutes (240 by default).");
        RULES.put("R07", "Sign-in brute force (HIGH): at least 5 failed sign-ins (password or MFA) for one user within 10 minutes.");
        RULES.put("R08", "Concurrent sessions (MEDIUM): one user with two sessions from different client IPs that overlap in time.");
        RULES.put("R09", "Slow stage (LOW): a stage took longer than the larger of the p99 of the same stage over the previous 7 days and a 15 s floor (needs at least 30 earlier instances).");
        RULES.put("R10", "Approval above limit (HIGH): the flow's amount is above the approver's imported approval limit, and the approval stage completed.");
        RULES.put("R11", "Duplicate submission (MEDIUM): the same requester, counterparty and amount submitted twice within 60 s.");
        RULES.put("R12", "Off-hours activity (LOW): the approval or final stage started between 00:00 and 05:00 in the business timezone.");
        RULES.put("R13", "Integration failure (HIGH): in the final stage, a call to an external system (a line with an external correlation ID) failed with an error or a provider status of 500 or more.");
        RULES.put("R14", "Authorization probing (HIGH): at least 5 permission denials for one user on at least 3 different resources within 10 minutes.");
        RULES.put("R15", "Scanner traffic (MEDIUM): one client IP sends at least 100 requests in 5 minutes with at least 60% answered 401, 403 or 404, or uses a known scanner user agent.");
    }
}
