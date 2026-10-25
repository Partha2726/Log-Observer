package com.ielop.platform.flowdef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The flow definitions loaded at start-up. The backend does not start with an
 * invalid or missing definition (fail fast); use {@link #of(List)} in tests.
 */
@Component
public class FlowDefinitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(FlowDefinitionRegistry.class);

    private final List<FlowDefinition> definitions;

    @Autowired
    public FlowDefinitionRegistry(@Value("${app.flows.dir:config/flows}") String dir) {
        Path path = resolve(dir);
        this.definitions = FlowDefinitionLoader.loadDirectory(path);
        definitions.forEach(d -> log.info("Loaded flow definition '{}' v{} with {} stages from {}",
                d.id(), d.version(), d.stages().size(), d.source()));
    }

    private FlowDefinitionRegistry(List<FlowDefinition> definitions) {
        this.definitions = List.copyOf(definitions);
    }

    public static FlowDefinitionRegistry of(List<FlowDefinition> definitions) {
        return new FlowDefinitionRegistry(definitions);
    }

    public List<FlowDefinition> all() {
        return definitions;
    }

    public Optional<FlowDefinition> byId(String id) {
        return definitions.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    /** The first definition whose {@code refPattern} matches the flowRef (section 7.1). */
    public Optional<FlowDefinition> forFlowRef(String flowRef) {
        return definitions.stream().filter(d -> d.matchesRef(flowRef)).findFirst();
    }

    static Path resolve(String dir) {
        Path path = Path.of(dir);
        if (!Files.isDirectory(path) && !path.isAbsolute() && Files.isDirectory(Path.of("..").resolve(dir))) {
            return Path.of("..").resolve(dir).normalize();
        }
        return path;
    }
}
