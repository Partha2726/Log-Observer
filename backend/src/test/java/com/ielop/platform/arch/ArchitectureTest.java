package com.ielop.platform.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Package boundaries (spec sections 8.1 and 20, ARCH-001 to ARCH-003).
 *
 * <p>Rules are written as "no class may ..." so they hold trivially while a
 * package does not exist yet and start biting as soon as it does.</p>
 */
@AnalyzeClasses(packages = "com.ielop.platform", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.ielop.platform.";

    /** ARCH-001: only {@code logsearch} talks to Elasticsearch. */
    @ArchTest
    static final ArchRule onlyLogsearchUsesTheElasticsearchClient = noClasses()
            .that().resideOutsideOfPackage(BASE + "logsearch..")
            .should().dependOnClassesThat().resideInAnyPackage("co.elastic.clients..")
            .allowEmptyShould(true);

    /** ARCH-002: nothing depends on {@code assistant}. */
    @ArchTest
    static final ArchRule nothingDependsOnAssistant = noClasses()
            .that().resideOutsideOfPackage(BASE + "assistant..")
            .should().dependOnClassesThat().resideInAPackage(BASE + "assistant..")
            .allowEmptyShould(true);

    /** ARCH-002: {@code trace} → {@code logsearch} and {@code flowdef}, never upwards. */
    @ArchTest
    static final ArchRule traceDoesNotDependOnDetection = noClasses()
            .that().resideInAPackage(BASE + "trace..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "detection..")
            .allowEmptyShould(true);

    /** ARCH-002: the lower layers never depend on the layers built on them. */
    @ArchTest
    static final ArchRule logsearchAndFlowdefStayAtTheBottom = noClasses()
            .that().resideInAnyPackage(BASE + "logsearch..", BASE + "flowdef..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "trace..", BASE + "detection..")
            .allowEmptyShould(true);

    /** ARCH-004: assistant tools are read-only: no repository save or delete, no JDBC update. */
    @ArchTest
    static final ArchRule assistantToolsAreReadOnly = noClasses()
            .that().areAnnotatedWith(BASE + "assistant.AssistantTool")
            .should().callMethodWhere(com.tngtech.archunit.base.DescribedPredicate.describe("a write method",
                    (com.tngtech.archunit.core.domain.JavaMethodCall call) -> {
                        String name = call.getTarget().getName();
                        String owner = call.getTarget().getOwner().getName();
                        boolean repository = call.getTarget().getOwner().isAssignableTo("org.springframework.data.repository.Repository");
                        boolean jdbc = owner.startsWith("org.springframework.jdbc.core.");
                        return (repository && (name.startsWith("save") || name.startsWith("delete")))
                                || (jdbc && (name.equals("update") || name.equals("batchUpdate") || name.equals("execute")));
                    }))
            .allowEmptyShould(true);

    /** ARCH-003: no code imports the earlier project's legacy modules. */
    @ArchTest
    static final ArchRule noLegacyModules = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    BASE + "incident..", BASE + "log..", BASE + "correlation..", BASE + "investigation..",
                    BASE + "timeline..", BASE + "topology..", BASE + "search..")
            .allowEmptyShould(true);
}
