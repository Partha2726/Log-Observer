package com.ielop.generator;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.concurrent.Callable;

/**
 * Command-line entry point (spec section 5.3).
 *
 * <p>Log4j2 must be configured before its first use, so this class only parses
 * options and sets system properties; {@link Generator} is created afterwards.</p>
 */
@Command(name = "generator", mixinStandardHelpOptions = true,
        description = "Writes deterministic fake Acme Cloud logs in four formats, with ground truth.")
public final class GeneratorMain implements Callable<Integer> {

    @Option(names = "--out", defaultValue = "../data/logs", description = "Log output root (default: ${DEFAULT-VALUE})")
    Path out;

    @Option(names = "--truth", defaultValue = "../data/ground-truth", description = "Ground-truth output folder (default: ${DEFAULT-VALUE})")
    Path truth;

    @Option(names = "--start", defaultValue = "2026-05-15T08:00",
            description = "First simulated day and time, local to --legacy-timezone (default: ${DEFAULT-VALUE})")
    LocalDateTime start;

    @Option(names = "--days", defaultValue = "7", description = "Simulated days (default: ${DEFAULT-VALUE})")
    int days;

    @Option(names = "--flows", defaultValue = "10000", description = "Number of flows (default: ${DEFAULT-VALUE})")
    int flows;

    @Option(names = "--users", defaultValue = "200", description = "Number of observed users (default: ${DEFAULT-VALUE})")
    int users;

    @Option(names = "--flow-definition", defaultValue = "../config/flows/purchase-request.yaml",
            description = "Flow definition YAML (default: ${DEFAULT-VALUE})")
    Path flowDefinition;

    @Option(names = "--scenario-mix", defaultValue = "default", description = "'default' or a YAML file (default: ${DEFAULT-VALUE})")
    String scenarioMix;

    @Option(names = "--legacy-timezone", defaultValue = "UTC",
            description = "Timezone the billing service (format D) and the gateway write in (default: ${DEFAULT-VALUE})")
    ZoneId legacyTimezone;

    @Option(names = "--seed", defaultValue = "42", description = "Same seed gives byte-identical output (default: ${DEFAULT-VALUE})")
    long seed;

    @Option(names = "--otel", defaultValue = "false", arity = "1", description = "Phase 3: add trace.id and span.id (default: ${DEFAULT-VALUE})")
    boolean otel;

    public static void main(String[] args) {
        System.exit(new CommandLine(new GeneratorMain()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        GeneratorConfig config = new GeneratorConfig(out.toAbsolutePath().normalize(), truth.toAbsolutePath().normalize(),
                start, days, flows, users, flowDefinition, scenarioMix, legacyTimezone, seed, otel);

        // Before Log4j2 starts: simulated clock, output root, legacy zone, UTC as the JVM default
        // (RFC 5424 timestamps then end in Z), and the generator's own configuration file.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.setProperty("log4j.Clock", "com.ielop.generator.clock.SimClock");
        System.setProperty("log4j2.configurationFile", "classpath:log4j2-generator.xml");
        System.setProperty("gen.out", config.out().toString());
        System.setProperty("gen.legacyTz", config.legacyZone().getId());
        // Phase 3: OpenTelemetry fields, in otel mode only; empty otherwise, so the default output is unchanged.
        System.setProperty("gen.otelD", config.otel() ? " trace_id=%X{trace_id}" : "");
        System.setProperty("gen.workflowTemplate", config.otel() ? "workflow-ecs-otel-template.json" : "workflow-ecs-template.json");

        Generator.Summary summary = new Generator(config).run();
        System.out.println(summary);
        return 0;
    }
}
