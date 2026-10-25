package com.ielop.generator;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Parsed CLI options (spec section 5.3). */
public record GeneratorConfig(
        Path out,
        Path truth,
        LocalDateTime start,
        int days,
        int flows,
        int users,
        Path flowDefinition,
        String scenarioMix,
        ZoneId legacyZone,
        long seed,
        boolean otel
) {
}
