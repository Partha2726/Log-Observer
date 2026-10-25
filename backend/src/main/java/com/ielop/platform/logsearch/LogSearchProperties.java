package com.ielop.platform.logsearch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.elasticsearch.*}: connection and the resource caps of spec section 8.10.
 *
 * @param url              Elasticsearch base URL
 * @param index            index pattern holding the parsed log events
 * @param username         read-only user ({@code backend_reader}); blank disables authentication
 * @param password         its password, from the environment only
 * @param timeout          per-request timeout, also sent to Elasticsearch as the search timeout
 * @param maxPageSize      largest {@code size} of one search call
 * @param totalHitsCap     {@code track_total_hits} cap; totals above it are lower bounds
 * @param seedCap          most hits fetched for one trace's seed query before it is marked truncated
 */
@ConfigurationProperties(prefix = "app.elasticsearch")
public record LogSearchProperties(
        String url,
        String index,
        String username,
        String password,
        Duration timeout,
        int maxPageSize,
        int totalHitsCap,
        int seedCap
) {
    public LogSearchProperties {
        url = url == null || url.isBlank() ? "http://localhost:9200" : url;
        index = index == null || index.isBlank() ? "logs-flow-*" : index;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        maxPageSize = maxPageSize <= 0 ? 1000 : maxPageSize;
        totalHitsCap = totalHitsCap <= 0 ? 10_000 : totalHitsCap;
        seedCap = seedCap <= 0 ? 5_000 : seedCap;
    }
}
