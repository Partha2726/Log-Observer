package com.ielop.platform.flowdef;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

/** {@code GET /api/flows/definitions}: the loaded flow definitions, for the UI's lanes and filters (section 8.2). */
@RestController
@Tag(name = "Flow definitions")
public class FlowDefinitionController {

    /** One stage as the UI needs it. */
    public record StageView(String name, int order, String actorRole, boolean canReject) {
    }

    /** One flow definition as the UI needs it. */
    public record FlowDefinitionView(String id, int version, String refPattern, List<String> roles, List<StageView> stages,
                                     boolean hasBusinessFields) {
    }

    private final FlowDefinitionRegistry registry;

    public FlowDefinitionController(FlowDefinitionRegistry registry) {
        this.registry = registry;
    }

    @Operation(summary = "Loaded flow definitions: stages, roles, reference pattern")
    @GetMapping("/api/flows/definitions")
    public List<FlowDefinitionView> definitions() {
        return registry.all().stream()
                .map(d -> new FlowDefinitionView(d.id(), d.version(), d.refPatternSource(), d.roles(),
                        d.stages().stream().map(s -> new StageView(s.name(), s.order(), s.actorRole(), s.canReject())).toList(),
                        !d.businessFields().isEmpty()))
                .toList();
    }
}
