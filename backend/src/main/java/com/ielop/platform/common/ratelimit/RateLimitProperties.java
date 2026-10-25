package com.ielop.platform.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.rate-limits.*} (section 8.10). */
@ConfigurationProperties(prefix = "app.rate-limits")
public record RateLimitProperties(int loginPerMinute, int assistantMessagesPerMinute, int summaryRequestsPerHour) {
    public RateLimitProperties {
        loginPerMinute = loginPerMinute <= 0 ? 10 : loginPerMinute;
        assistantMessagesPerMinute = assistantMessagesPerMinute <= 0 ? 20 : assistantMessagesPerMinute;
        summaryRequestsPerHour = summaryRequestsPerHour <= 0 ? 30 : summaryRequestsPerHour;
    }
}
