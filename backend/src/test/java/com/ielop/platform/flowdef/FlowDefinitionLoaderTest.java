package com.ielop.platform.flowdef;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Flow definition loading and validation (T11): the demo, the test-only two-stage flow, and invalid files. */
class FlowDefinitionLoaderTest {

    static final Path FLOWS = Path.of("..", "config", "flows");
    static final Path TWO_STAGE = Path.of("src", "test", "resources", "flows", "test-two-stage.yaml");
    final FlowDefinitionLoader loader = new FlowDefinitionLoader(FLOWS.resolve(FlowDefinitionLoader.SCHEMA_FILE));

    private static final String VALID = """
            id: tiny
            version: 1
            refPattern: '^T-\\d+$'
            roles: [author]
            stages:
              - name: Write
                actorRole: author
                match:
                  - { service: api, httpMethod: POST, urlPath: '^/write$' }
                successEvents: [written]
            """;

    @Test
    void demoFlowLoadsWithItsStagesRolesAndMatchers() {
        List<FlowDefinition> defs = FlowDefinitionLoader.loadDirectory(FLOWS);
        FlowDefinition demo = defs.stream().filter(d -> d.id().equals("purchase-request")).findFirst().orElseThrow();

        assertThat(demo.stages()).extracting(StageDefinition::name).containsExactly("SignIn", "Create", "Approve", "Execute");
        assertThat(demo.stages()).extracting(StageDefinition::order).containsExactly(1, 2, 3, 4);
        assertThat(demo.roles()).containsExactly("requester", "approver", "executor");
        assertThat(demo.matchesRef("REQ-20260515-000944")).isTrue();
        assertThat(demo.matchesRef("REQ-1")).isFalse();
        assertThat(demo.stage("SignIn").orElseThrow().isSessionStage()).isTrue();
        assertThat(demo.stage("Approve").orElseThrow().canReject()).isTrue();
        assertThat(demo.stageOf("gateway", "POST", "/api/requests/REQ-20260515-000944/approve", null))
                .map(StageDefinition::name).contains("Approve");
        assertThat(demo.stageOf("workflow-service", null, null, "request.created")).map(StageDefinition::name).contains("Create");
        assertThat(demo.isSignOut("auth-service", null, null, "SESSION_EXPIRED")).isTrue();
        assertThat(demo.sessionEventActions()).contains("LOGIN_OK", "MFA_FAILED", "LOGOUT", "SESSION_EXPIRED");
        assertThat(demo.signInSuccessEvents()).containsExactly("LOGIN_OK");
        assertThat(demo.businessFields()).containsEntry("counterparty", "vendor_account");
        assertThat(demo.sha256()).hasSize(64);
    }

    @Test
    void testOnlyTwoStageFlowLoadsWithTheSameCode() {
        FlowDefinition two = loader.load(TWO_STAGE);
        assertThat(two.id()).isEqualTo("test-two-stage");
        assertThat(two.stages()).extracting(StageDefinition::name).containsExactly("Open", "Resolve");
        assertThat(two.signOut()).isEmpty();
        assertThat(two.businessFields()).isEmpty();
        assertThat(two.matchesRef("TKT-000123")).isTrue();
    }

    @Test
    void aValidMinimalDefinitionParses() {
        assertThat(loader.parse(VALID, "x", "inline").finalStage().name()).isEqualTo("Write");
    }

    @Test
    void missingStagesIsRejectedBySchema() {
        String yaml = "id: tiny\nversion: 1\nrefPattern: '^T$'\nroles: [author]\n";
        assertThatThrownBy(() -> loader.parse(yaml, "x", "inline"))
                .isInstanceOf(FlowDefinitionException.class)
                .hasMessageContaining("stages");
    }

    @Test
    void badRegularExpressionIsRejected() {
        String yaml = VALID.replace("'^/write$'", "'^/write($'");
        assertThatThrownBy(() -> loader.parse(yaml, "x", "inline"))
                .isInstanceOf(FlowDefinitionException.class)
                .hasMessageContaining("not a valid regular expression");
    }

    @Test
    void badRefPatternIsRejected() {
        String yaml = VALID.replace("'^T-\\d+$'", "'^T-[$'");
        assertThatThrownBy(() -> loader.parse(yaml, "x", "inline")).hasMessageContaining("refPattern");
    }

    @Test
    void unknownActorRoleIsRejected() {
        String yaml = VALID.replace("actorRole: author", "actorRole: reviewer");
        assertThatThrownBy(() -> loader.parse(yaml, "x", "inline")).hasMessageContaining("not in roles");
    }

    @Test
    void reservedSignOutStageNameUnknownKeysAndRejectRulesAreRejected() {
        assertThatThrownBy(() -> loader.parse(VALID.replace("name: Write", "name: signOut"), "x", "inline"))
                .isInstanceOf(FlowDefinitionException.class);
        assertThatThrownBy(() -> loader.parse(VALID + "colour: blue\n", "x", "inline"))
                .isInstanceOf(FlowDefinitionException.class);
        assertThatThrownBy(() -> loader.parse(VALID.replace("successEvents: [written]", "successEvents: [written]\n    canReject: true"), "x", "inline"))
                .hasMessageContaining("canReject but lists no rejectEvents");
    }

    @Test
    void matcherNeedsAPathOrAnAction() {
        String yaml = VALID.replace("{ service: api, httpMethod: POST, urlPath: '^/write$' }", "{ service: api }");
        assertThatThrownBy(() -> loader.parse(yaml, "x", "inline")).isInstanceOf(FlowDefinitionException.class);
    }
}
