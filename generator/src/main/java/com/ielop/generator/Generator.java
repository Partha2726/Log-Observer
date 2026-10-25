package com.ielop.generator;

import com.ielop.generator.emit.Emitter;
import com.ielop.generator.emit.OtelTraces;
import com.ielop.generator.emit.Event;
import com.ielop.generator.emit.Ids;
import com.ielop.generator.emit.MessageCatalogue;
import com.ielop.generator.emit.NoiseEmitter;
import com.ielop.generator.emit.StageEmitter;
import com.ielop.generator.flow.FlowDefinition;
import com.ielop.generator.model.Flow;
import com.ielop.generator.model.Scenario;
import com.ielop.generator.model.Service;
import com.ielop.generator.model.User;
import com.ielop.generator.model.UserDirectory;
import com.ielop.generator.scenario.ScenarioMix;
import com.ielop.generator.truth.GroundTruthWriter;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.stream.Stream;

/**
 * Runs one generation: schedules flows, builds their events, merges them with
 * background noise in time order and writes them through the {@link Emitter}.
 *
 * <p>Events are buffered in a priority queue ordered by {@code (timestamp, seq)}.
 * Flows are built in order of their start time and never emit before it, and
 * noise is built one minute at a time ahead of the next flow start, so every
 * event earlier than the next flow's start can be written safely. Memory stays
 * bounded by the flows in flight, not by the run size.</p>
 */
public final class Generator {

    private static final Comparator<Event> ORDER = Comparator.comparingLong(Event::tsMillis).thenComparingLong(Event::seq);
    private static final LocalTime BUSINESS_START = LocalTime.of(8, 0);
    private static final long BUSINESS_SPAN_MILLIS = 11 * 3_600_000L;
    private static final long NOISE_STREAM = 0x6E6F697365L;
    private static final long USER_STREAM = -1L;
    private static final long SCENARIO_STREAM = -2L;

    private final GeneratorConfig config;
    private final PriorityQueue<Event> queue = new PriorityQueue<>(ORDER);
    private long seq;

    public Generator(GeneratorConfig config) {
        this.config = config;
    }

    public record Summary(long events, int flows, Path out) {
        @Override
        public String toString() {
            return "Generated " + events + " events for " + flows + " flows into " + out;
        }
    }

    public Summary run() throws IOException {
        FlowDefinition definition = FlowDefinition.load(config.flowDefinition()).requireDemoShape();
        cleanOutput();

        ZoneId zone = config.legacyZone();
        GroundTruthWriter truth = new GroundTruthWriter(config.truth(), definition);
        Emitter emitter = new Emitter(zone, truth, config.otel() ? new OtelTraces(config.seed()) : null);
        UserDirectory users = UserDirectory.create(config.users(), new SplittableRandom(mix(config.seed(), USER_STREAM)));
        truth.users(users);
        List<Scenario> scenarios = ScenarioMix.load(config.scenarioMix())
                .assign(config.flows(), new SplittableRandom(mix(config.seed(), SCENARIO_STREAM)));
        MessageCatalogue catalogue = new MessageCatalogue(zone);
        StageEmitter stages = new StageEmitter(definition, catalogue, users, this::push);

        long startMillis = config.start().atZone(zone).toInstant().toEpochMilli();
        long endMillis = config.start().plusDays(config.days()).atZone(zone).toInstant().toEpochMilli();
        double flowsPerDay = config.flows() / (double) config.days();
        NoiseEmitter noise = new NoiseEmitter(stages, users, this::push, m -> mix(config.seed() ^ NOISE_STREAM, m),
                zone, startMillis, flowsPerDay);

        List<Long> starts = scheduleFlows(zone);
        List<String> doneRefs = new ArrayList<>();
        List<Long> doneStarts = new ArrayList<>();
        stages.incidents(truth::incident);
        stages.probeTargets((before, count, r) -> probeTargets(doneRefs, doneStarts, before, count, r, zone));
        int duplicates = 0;
        for (int i = 0; i < starts.size(); i++) {
            long flowStart = starts.get(i);
            noise.generateUntil(flowStart);
            drainBefore(flowStart, emitter);
            SplittableRandom rnd = new SplittableRandom(mix(config.seed(), i));
            Flow flow = planFlow(i, i + 1, flowStart, scenarios.get(i), users, zone, rnd);
            Flow duplicate = flow.scenario() == Scenario.DUPLICATE_SUBMIT
                    ? flow.duplicate(Ids.flowRef(Instant.ofEpochMilli(flowStart).atZone(zone).toLocalDate(),
                            config.flows() + 1 + duplicates++))
                    : null;
            for (Flow written : stages.emitFlow(flow, duplicate, rnd)) {
                truth.flow(written);
            }
            doneRefs.add(flow.flowRef());
            doneStarts.add(flowStart);
        }
        noise.generateUntil(endMillis);
        drainBefore(Long.MAX_VALUE, emitter);
        truth.close();
        LogManager.shutdown();
        return new Summary(emitter.count(), starts.size(), config.out());
    }

    /** Flow start times: uniform over the business day (08:00 to 19:00 local) of each simulated day, sorted. */
    private List<Long> scheduleFlows(ZoneId zone) {
        SplittableRandom rnd = new SplittableRandom(config.seed());
        LocalDate firstDay = config.start().toLocalDate();
        List<Long> starts = new ArrayList<>(config.flows());
        for (int i = 0; i < config.flows(); i++) {
            LocalDate day = firstDay.plusDays(rnd.nextInt(config.days()));
            long dayStart = day.atTime(BUSINESS_START).atZone(zone).toInstant().toEpochMilli();
            starts.add(dayStart + (long) (rnd.nextDouble() * BUSINESS_SPAN_MILLIS));
        }
        starts.sort(null);
        return starts;
    }

    private Flow planFlow(int index, int sequence, long start, Scenario scenario, UserDirectory users, ZoneId zone,
                          SplittableRandom rnd) {
        User requester = users.randomRequester(rnd);
        // Amounts 10 to 50,000 USD, log-uniform so small requests are common.
        double amount = Math.exp(Math.log(10) + rnd.nextDouble() * (Math.log(50_000) - Math.log(10)));
        if (scenario == Scenario.LIMIT_BREACH) {
            // Above the smallest approval limit, so some approver's limit is exceeded (R10).
            amount = 6_000 + rnd.nextDouble() * 43_000;
        }
        BigDecimal value = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
        if (scenario == Scenario.SELF_APPROVAL) {
            value = value.min(new BigDecimal("4999.99"));
            requester = users.requesterWhoCanApprove(value, rnd);
        }
        String vendorAccount = "999" + String.format("%09d", rnd.nextInt(1_000_000_000));
        String costCentre = "CC-" + (100 + rnd.nextInt(900));
        String ref = Ids.flowRef(Instant.ofEpochMilli(start).atZone(zone).toLocalDate(), sequence);
        return new Flow(index, ref, scenario, start, requester, value, "USD", vendorAccount, costCentre);
    }

    /** Probe targets: refs of flows started at least six hours before (finished by then), else guessed refs. */
    private static List<String> probeTargets(List<String> refs, List<Long> starts, long before, int count,
                                             SplittableRandom rnd, ZoneId zone) {
        int eligible = 0;
        while (eligible < starts.size() && starts.get(eligible) < before) {
            eligible++;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(eligible > 0 ? refs.get(rnd.nextInt(eligible))
                    : Ids.flowRef(Instant.ofEpochMilli(before).atZone(zone).toLocalDate(), 900_000 + rnd.nextInt(99_999)));
        }
        return out;
    }

    private void push(Event.Builder builder) {
        queue.add(builder.build(seq++));
    }

    private void drainBefore(long watermark, Emitter emitter) {
        while (!queue.isEmpty() && queue.peek().tsMillis() < watermark) {
            emitter.emit(queue.poll());
        }
    }

    private void cleanOutput() throws IOException {
        for (Service service : Service.values()) {
            Path dir = config.out().resolve(service.folder());
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(Files::isRegularFile).forEach(Generator::delete);
                }
            }
            Files.createDirectories(dir);
        }
    }

    private static void delete(Path file) {
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Derives an independent, reproducible stream per flow from the run seed (SplitMix64 finaliser). */
    static long mix(long seed, long index) {
        long z = seed + 0x9E3779B97F4A7C15L * (index + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
