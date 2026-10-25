package com.ielop.generator.flow;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The parts of a flow definition (spec section 2.6) the generator needs: the
 * flow ID, its flowRef pattern, its roles and its stage names in order.
 *
 * <p>The generator simulates the demo flow's services, so it requires a
 * definition shaped like the demo (four stages, three roles). It reads stage
 * names from the file so ground-truth labels always match what the backend
 * loads.</p>
 */
public record FlowDefinition(String id, int version, Pattern refPattern, List<String> roles, List<String> stages) {

    public static FlowDefinition load(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            return fromMap(root);
        }
    }

    @SuppressWarnings("unchecked")
    static FlowDefinition fromMap(Map<String, Object> root) {
        String id = (String) require(root, "id");
        int version = ((Number) require(root, "version")).intValue();
        Pattern ref = Pattern.compile((String) require(root, "refPattern"));
        List<String> roles = (List<String>) require(root, "roles");
        List<Map<String, Object>> stageMaps = (List<Map<String, Object>>) require(root, "stages");
        List<String> stages = stageMaps.stream().map(s -> (String) require(s, "name")).toList();
        return new FlowDefinition(id, version, ref, List.copyOf(roles), stages);
    }

    /** The generator's emitters produce sign-in, create, approve and execute; the names come from the file. */
    public FlowDefinition requireDemoShape() {
        if (stages.size() != 4 || roles.size() != 3) {
            throw new IllegalArgumentException("The generator simulates a four-stage, three-role flow; '"
                    + id + "' has " + stages.size() + " stages and " + roles.size() + " roles");
        }
        return this;
    }

    public String signInStage() {
        return stages.get(0);
    }

    public String createStage() {
        return stages.get(1);
    }

    public String approveStage() {
        return stages.get(2);
    }

    public String executeStage() {
        return stages.get(3);
    }

    private static Object require(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Flow definition is missing '" + key + "'");
        }
        return value;
    }
}
