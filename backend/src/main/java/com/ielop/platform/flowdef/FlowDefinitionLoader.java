package com.ielop.platform.flowdef;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Reads flow definition YAML files, validates each against the JSON schema and
 * then semantically (regular expressions compile, roles exist, stage names are
 * unique, reject events only on {@code canReject} stages), and builds
 * {@link FlowDefinition}s.
 */
public final class FlowDefinitionLoader {

    public static final String SCHEMA_FILE = "flow-definition.schema.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JsonSchema schema;

    public FlowDefinitionLoader(Path schemaFile) {
        try {
            JsonNode schemaNode = JSON.readTree(Files.readString(schemaFile, StandardCharsets.UTF_8));
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read flow definition schema " + schemaFile, e);
        }
    }

    /** Every {@code *.yaml} / {@code *.yml} in {@code dir}, in file-name order, using the schema in the same folder. */
    public static List<FlowDefinition> loadDirectory(Path dir) {
        FlowDefinitionLoader loader = new FlowDefinitionLoader(dir.resolve(SCHEMA_FILE));
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> yaml = files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).sorted().toList();
            if (yaml.isEmpty()) {
                throw new FlowDefinitionException(dir.toString(), List.of("no flow definition files"));
            }
            List<FlowDefinition> defs = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Path file : yaml) {
                FlowDefinition def = loader.load(file);
                if (!ids.add(def.id())) {
                    throw new FlowDefinitionException(file.toString(), List.of("duplicate flow id '" + def.id() + "'"));
                }
                defs.add(def);
            }
            return List.copyOf(defs);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list flow definitions in " + dir, e);
        }
    }

    public FlowDefinition load(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return parse(new String(bytes, StandardCharsets.UTF_8), sha256(bytes), file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read flow definition " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    public FlowDefinition parse(String yamlText, String sha256, String source) {
        Object raw;
        try {
            raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
        } catch (RuntimeException e) {
            throw new FlowDefinitionException(source, List.of("not valid YAML: " + e.getMessage()));
        }
        if (!(raw instanceof Map<?, ?>)) {
            throw new FlowDefinitionException(source, List.of("top level must be a mapping"));
        }
        JsonNode node = JSON.valueToTree(raw);
        List<String> problems = new ArrayList<>();
        for (ValidationMessage m : schema.validate(node)) {
            problems.add(m.getMessage());
        }
        if (!problems.isEmpty()) {
            problems.sort(null);
            throw new FlowDefinitionException(source, problems);
        }
        Map<String, Object> root = (Map<String, Object>) raw;
        List<String> roles = (List<String>) root.get("roles");
        Pattern refPattern = compile((String) root.get("refPattern"), "refPattern", problems);

        List<StageDefinition> stages = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int order = 1;
        for (Map<String, Object> s : (List<Map<String, Object>>) root.get("stages")) {
            String name = (String) s.get("name");
            if (!names.add(name)) {
                problems.add("duplicate stage name '" + name + "'");
            }
            String actorRole = (String) s.get("actorRole");
            if (actorRole != null && !roles.contains(actorRole)) {
                problems.add("stage '" + name + "' has actorRole '" + actorRole + "', which is not in roles");
            }
            boolean canReject = Boolean.TRUE.equals(s.get("canReject"));
            Set<String> reject = new LinkedHashSet<>(strings(s.get("rejectEvents")));
            if (!reject.isEmpty() && !canReject) {
                problems.add("stage '" + name + "' lists rejectEvents but is not canReject");
            }
            if (canReject && reject.isEmpty()) {
                problems.add("stage '" + name + "' is canReject but lists no rejectEvents");
            }
            stages.add(new StageDefinition(name, order++, actorRole, canReject,
                    rules((List<Map<String, Object>>) s.get("match"), "stage '" + name + "'", problems),
                    Set.copyOf(strings(s.get("successEvents"))), Set.copyOf(reject)));
        }
        List<MatchRule> signOut = List.of();
        if (root.get("signOut") instanceof Map<?, ?> so) {
            signOut = rules((List<Map<String, Object>>) so.get("match"), "signOut", problems);
        }
        Map<String, String> business = new LinkedHashMap<>();
        if (root.get("businessFields") instanceof Map<?, ?> bf) {
            bf.forEach((k, v) -> business.put(k.toString(), v.toString()));
        }
        if (!problems.isEmpty()) {
            throw new FlowDefinitionException(source, problems);
        }
        return new FlowDefinition((String) root.get("id"), ((Number) root.get("version")).intValue(),
                (String) root.get("refPattern"), refPattern, List.copyOf(roles), List.copyOf(stages), signOut,
                Map.copyOf(business), sha256, source);
    }

    private static List<MatchRule> rules(List<Map<String, Object>> raw, String where, List<String> problems) {
        List<MatchRule> out = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            String path = (String) m.get("urlPath");
            out.add(new MatchRule((String) m.get("service"), (String) m.get("httpMethod"),
                    path == null ? null : compile(path, where + " urlPath", problems),
                    Set.copyOf(strings(m.get("eventAction")))));
        }
        return List.copyOf(out);
    }

    private static Pattern compile(String regex, String where, List<String> problems) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            problems.add(where + " is not a valid regular expression: " + e.getDescription());
            return Pattern.compile("(?!)");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object v) {
        return v == null ? List.of() : (List<String>) v;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
