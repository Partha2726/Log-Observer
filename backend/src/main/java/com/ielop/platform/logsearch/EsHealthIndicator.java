package com.ielop.platform.logsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** {@code elasticsearch} component of {@code /actuator/health} (section 8.8): cluster status, DOWN when unreachable. */
@Component("elasticsearch")
@ConditionalOnEnabledHealthIndicator("elasticsearch")
public class EsHealthIndicator implements HealthIndicator {

    private final ElasticsearchClient client;

    public EsHealthIndicator(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            var health = client.cluster().health(h -> h.timeout(t -> t.time("2s")));
            Health.Builder builder = health.status() == HealthStatus.Red ? Health.down() : Health.up();
            return builder.withDetail("cluster", health.clusterName())
                    .withDetail("status", health.status().jsonValue())
                    .build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
