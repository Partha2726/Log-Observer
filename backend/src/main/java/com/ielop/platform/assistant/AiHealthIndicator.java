package com.ielop.platform.assistant;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * The {@code ai} health component (section 8.8): {@code UP} with provider and model when enabled,
 * {@code DISABLED} (never {@code DOWN}) with the reason when not. It makes no provider call, so a
 * health check never spends tokens.
 */
@Component("ai")
public class AiHealthIndicator implements HealthIndicator {

    public static final Status DISABLED = new Status("DISABLED", "AI features are switched off");

    private final AiAvailability availability;
    private final AiGateway gateway;

    public AiHealthIndicator(AiAvailability availability, AiGateway gateway) {
        this.availability = availability;
        this.gateway = gateway;
    }

    @Override
    public Health health() {
        Health.Builder b = availability.enabled() ? Health.up() : Health.status(DISABLED).withDetail("reason", availability.reason());
        b.withDetail("provider", availability.provider()).withDetail("model", availability.model());
        if (gateway instanceof FallbackAiGateway chain) {
            b.withDetail("chain", chain.providers().stream().map(g -> g.provider() + "/" + g.model()).toList());
        }
        return b.build();
    }
}
