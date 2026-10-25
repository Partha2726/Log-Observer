package com.ielop.generator.scenario;

import com.ielop.generator.model.Scenario;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Scenario weights from {@code mix-default.yaml} or a user file, turned into an
 * exact, shuffled assignment of one scenario per flow.
 */
public final class ScenarioMix {

    private final Map<Scenario, Double> weights;

    private ScenarioMix(Map<Scenario, Double> weights) {
        this.weights = weights;
    }

    public static ScenarioMix load(String spec) throws IOException {
        if ("default".equals(spec)) {
            try (InputStream in = ScenarioMix.class.getResourceAsStream("/mix-default.yaml")) {
                return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }
        try (Reader reader = Files.newBufferedReader(Path.of(spec))) {
            return parse(reader);
        }
    }

    static ScenarioMix parse(Reader reader) {
        Map<String, Object> raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        Map<Scenario, Double> weights = new EnumMap<>(Scenario.class);
        raw.forEach((name, weight) -> {
            Scenario scenario;
            try {
                scenario = Scenario.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown or not yet implemented scenario in mix: " + name);
            }
            double w = ((Number) weight).doubleValue();
            if (w < 0) {
                throw new IllegalArgumentException("Negative weight for " + name);
            }
            weights.put(scenario, w);
        });
        if (weights.values().stream().mapToDouble(Double::doubleValue).sum() <= 0) {
            throw new IllegalArgumentException("Scenario mix has no positive weight");
        }
        return new ScenarioMix(weights);
    }

    /** Exactly {@code flows} scenarios in the mix's proportions (largest remainder), shuffled deterministically. */
    public List<Scenario> assign(int flows, SplittableRandom rnd) {
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<Scenario, Integer> counts = new EnumMap<>(Scenario.class);
        List<Map.Entry<Scenario, Double>> remainders = new ArrayList<>();
        int assigned = 0;
        for (Map.Entry<Scenario, Double> e : weights.entrySet()) {
            double exact = flows * e.getValue() / total;
            int floor = (int) Math.floor(exact);
            counts.put(e.getKey(), floor);
            assigned += floor;
            remainders.add(Map.entry(e.getKey(), exact - floor));
        }
        remainders.sort(Map.Entry.<Scenario, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())));
        for (int i = 0; assigned < flows; i++, assigned++) {
            Scenario s = remainders.get(i % remainders.size()).getKey();
            counts.merge(s, 1, Integer::sum);
        }
        List<Scenario> out = new ArrayList<>(flows);
        counts.forEach((s, n) -> {
            for (int i = 0; i < n; i++) {
                out.add(s);
            }
        });
        for (int i = out.size() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            Scenario tmp = out.get(i);
            out.set(i, out.get(j));
            out.set(j, tmp);
        }
        return out;
    }
}
