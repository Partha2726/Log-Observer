package com.ielop.platform.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.EsFixtures;
import com.ielop.platform.trace.StoredTrace;
import com.ielop.platform.trace.TraceService;
import com.ielop.platform.trace.entity.TraceEventLinkEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The redacted trace context (T25, section 11.3): deterministic, limited, with a server-side evidence map. */
class TraceContextBuilderTest extends ElasticsearchIntegrationTest {

    static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d{9,18}(?!\\d)");

    @Autowired
    private TraceContextBuilder builder;
    @Autowired
    private TraceService traces;

    private static String ref(String scenario) {
        return EsFixtures.demoFlows().stream().filter(f -> f.get("scenario").asText().equals(scenario)).map(f -> f.get("flowRef").asText())
                .findFirst().orElseThrow();
    }

    @Test
    void theSameTraceGivesTheSameContextAndHash() {
        String ref = ref("FAIL_DB_TIMEOUT_APPROVE");
        TraceContextBuilder.Built a = builder.build(ref);
        TraceContextBuilder.Built b = builder.build(ref);
        assertThat(a.hash()).hasSize(64).isEqualTo(b.hash());
        assertThat(builder.json(a.context())).isEqualTo(builder.json(b.context()));
        assertThat(a.evidenceDocIds()).isEqualTo(b.evidenceDocIds());
    }

    @Test
    void evidenceIsLimitedPrioritisedAndMappedOnTheServerOnly() {
        String ref = ref("FAIL_DB_TIMEOUT_APPROVE");
        TraceContextBuilder.Built built = builder.build(ref);
        TraceContext c = built.context();
        StoredTrace stored = traces.findStored(ref).orElseThrow();

        assertThat(c.contextVersion()).isEqualTo("1");
        assertThat(c.trace().overallStatus()).isEqualTo("FAILED");
        assertThat(c.trace().firstFailure().stage()).isEqualTo("Approve");
        assertThat(c.trace().stages()).extracting(TraceContext.Stage::stage).containsExactly("SignIn", "Create", "Approve", "Execute");
        assertThat(c.evidence()).hasSizeLessThanOrEqualTo(TraceContextBuilder.MAX_EVIDENCE).isNotEmpty();
        assertThat(c.evidence()).extracting(TraceContext.Evidence::id).first().isEqualTo("ev1");
        assertThat(c.truncated().evidenceOmitted()).isEqualTo(stored.links().size() - c.evidence().size());

        // Every error line of the trace is in the context (priority 1), unless more than 40 exist.
        List<String> errorDocs = stored.links().stream().filter(TraceEventLinkEntity::isError).map(TraceEventLinkEntity::getEsDocId).toList();
        assertThat(built.evidenceDocIds().values()).containsAll(errorDocs);
        // The first failure's document is cited, and evidence is in time order.
        assertThat(built.evidenceDocIds().values()).contains(stored.trace().getFirstFailureDocId());
        List<String> ts = c.evidence().stream().map(TraceContext.Evidence::ts).toList();
        assertThat(ts).isSorted();
        // The model never sees document IDs.
        String json = builder.json(c);
        for (String docId : built.evidenceDocIds().values()) {
            assertThat(json).doesNotContain(docId);
        }
        assertThat(LONG_DIGITS.matcher(json).find()).as("no 9-18 digit run reaches the context").isFalse();
        c.evidence().forEach(e -> assertThat(e.text().length()).isLessThanOrEqualTo(600));
    }

    @Test
    void everyFixtureTraceBuildsAContext() {
        for (JsonNode f : EsFixtures.demoFlows()) {
            TraceContextBuilder.Built b = builder.build(f.get("flowRef").asText());
            assertThat(b.context().trace().flowRef()).isEqualTo(f.get("flowRef").asText());
            assertThat(b.evidenceDocIds()).hasSize(b.context().evidence().size());
        }
    }
}
