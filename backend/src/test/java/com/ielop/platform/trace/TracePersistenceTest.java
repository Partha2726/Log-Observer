package com.ielop.platform.trace;

import com.ielop.platform.support.IntegrationTest;
import com.ielop.platform.trace.entity.TraceActorEntity;
import com.ielop.platform.trace.entity.TraceEntity;
import com.ielop.platform.trace.entity.TraceEventLinkEntity;
import com.ielop.platform.trace.entity.TraceStageEntity;
import com.ielop.platform.trace.repo.TraceActorRepository;
import com.ielop.platform.trace.repo.TraceEventLinkRepository;
import com.ielop.platform.trace.repo.TraceRepository;
import com.ielop.platform.trace.repo.TraceStageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V2 schema round trip through the JPA entities; Hibernate {@code validate} already passed at start-up. */
class TracePersistenceTest extends IntegrationTest {

    @Autowired
    private TraceRepository traces;
    @Autowired
    private TraceStageRepository stages;
    @Autowired
    private TraceActorRepository actors;
    @Autowired
    private TraceEventLinkRepository links;
    @Autowired
    private JdbcTemplate jdbc;

    /** The round-trip rows sit on the demo fixture's day; remove them so stats and contract tests see the fixture only. */
    @org.junit.jupiter.api.AfterEach
    void removeRoundTripRows() {
        jdbc.update("DELETE FROM trace WHERE flow_ref IN ('REQ-20260515-900001', 'REQ-20260515-900002')");
    }

    private TraceEntity newTrace(String ref, String status) {
        TraceEntity t = new TraceEntity();
        t.setFlowRef(ref);
        t.setFlowId("purchase-request");
        t.setFlowVersion(1);
        t.setOverallStatus(status);
        t.setFirstSeen(Instant.parse("2026-05-15T13:10:02.114Z"));
        t.setLastSeen(Instant.parse("2026-05-15T13:42:10.552Z"));
        t.setAmount(new BigDecimal("2500.00"));
        t.setCurrency("USD");
        t.setCounterpartyLast4("6789");
        t.setObservedRatio(new BigDecimal("0.85"));
        t.setAssemblyVersion(1);
        t.setConfigHash("a".repeat(64));
        t.setAssembledAt(Instant.parse("2026-05-16T00:00:00Z"));
        return t;
    }

    @Test
    void traceStageActorAndLinkRoundTrip() {
        TraceEntity t = traces.save(newTrace("REQ-20260515-900001", "FAILED"));

        TraceStageEntity s = new TraceStageEntity();
        s.setTraceId(t.getId());
        s.setStage("Approve");
        s.setStageOrder(3);
        s.setStatus("FAILED");
        s.setErrorCount(2);
        stages.save(s);
        actors.save(new TraceActorEntity(t.getId(), "approver", "usr-2210"));
        TraceEventLinkEntity l = new TraceEventLinkEntity();
        l.setTraceId(t.getId());
        l.setEsDocId("9f2c".repeat(16));
        l.setStage("Approve");
        l.setService("billing-service");
        l.setEvidenceClass("CORRELATED");
        l.setConfidence(new BigDecimal("0.80"));
        l.setLinkReason("THREAD");
        l.setEventTs(Instant.parse("2026-05-15T13:11:27.711Z"));
        l.setSeq(0);
        l.setError(true);
        links.save(l);

        assertThat(traces.findByFlowRef("REQ-20260515-900001")).get()
                .satisfies(found -> assertThat(found.getCurrency()).isEqualTo("USD"));
        assertThat(stages.findByTraceIdOrderByStageOrder(t.getId())).singleElement()
                .satisfies(st -> assertThat(st.getStatus()).isEqualTo("FAILED"));
        assertThat(actors.findByTraceIdOrderByRole(t.getId())).singleElement()
                .satisfies(a -> assertThat(a.getUserId()).isEqualTo("usr-2210"));
        assertThat(links.findByTraceIdOrderBySeq(t.getId())).singleElement()
                .satisfies(link -> assertThat(link.getConfidence()).isEqualByComparingTo("0.80"));
    }

    @Test
    void statusCheckConstraintsRejectUnknownValues() {
        assertThatThrownBy(() -> traces.saveAndFlush(newTrace("REQ-20260515-900002", "BROKEN")))
                .isInstanceOf(DataIntegrityViolationException.class);
        Integer rejected = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname IN ('ck_trace_overall_status','ck_trace_stage_status',"
                        + "'ck_trace_event_link_class','ck_trace_event_link_reason')", Integer.class);
        assertThat(rejected).isEqualTo(4);
    }
}
