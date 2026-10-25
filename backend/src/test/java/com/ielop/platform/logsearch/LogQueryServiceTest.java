package com.ielop.platform.logsearch;

import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.EsFixtures;
import com.ielop.platform.support.EsFixtures.Doc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * logsearch against a real Elasticsearch seeded with the demo fixture (T10).
 * Expected results are computed from the fixture itself, so they do not depend
 * on which flows the fixture happens to contain.
 */
class LogQueryServiceTest extends ElasticsearchIntegrationTest {

    @Autowired
    private LogQueryService logs;
    @Autowired
    private EsHealthIndicator health;

    private static final Comparator<Doc> ORDER = Comparator.<Doc, String>comparing(d -> d.text("@timestamp"))
            .thenComparing(d -> d.text("log.file.path"))
            .thenComparingLong(d -> Long.parseLong(d.text("log.offset")));

    private static List<String> expectedIds(Predicate<Doc> filter) {
        return EsFixtures.demoDocs().stream().filter(filter).sorted(ORDER).map(Doc::id).toList();
    }

    private static List<String> ids(List<LogHit> hits) {
        return hits.stream().map(LogHit::id).toList();
    }

    private static String aFlowRef() {
        return EsFixtures.demoFlows().get(0).get("flowRef").asText();
    }

    @Test
    void termQueryByFlowRefReturnsEveryCarrierInDeterministicOrder() {
        String ref = aFlowRef();
        HitList result = logs.findAll("test", LogCriteria.builder().flowRef(ref).build(), 5_000);

        assertThat(result.truncated()).isFalse();
        assertThat(ids(result.hits())).isNotEmpty().containsExactlyElementsOf(expectedIds(d -> d.list("flow.refs").contains(ref)));
        LogHit first = result.hits().get(0);
        assertThat(first.flowRefs()).contains(ref);
        assertThat(first.timestamp()).isNotNull();
        assertThat(first.filePath()).startsWith("/logs/");
    }

    @Test
    void requestIdExpansionFindsEveryLineOfThoseCalls() {
        String ref = aFlowRef();
        Set<String> rids = EsFixtures.demoDocs().stream()
                .filter(d -> d.list("flow.refs").contains(ref) && d.text("http.request.id") != null)
                .map(d -> d.text("http.request.id")).collect(Collectors.toSet());
        HitList result = logs.findAll("test", LogCriteria.builder().requestIds(rids).build(), 5_000);

        assertThat(ids(result.hits())).containsExactlyElementsOf(expectedIds(d -> rids.contains(d.text("http.request.id"))));
        // Lines that only share the request ID (no flowRef of their own) are found too.
        assertThat(result.hits()).anyMatch(h -> h.flowRefs().isEmpty());
    }

    @Test
    void sessionAndActionFiltersCombine() {
        Doc login = EsFixtures.demoDocs().stream().filter(d -> d.text("session.id") != null
                && "auth-service".equals(d.text("service.name"))).findFirst().orElseThrow();
        String session = login.text("session.id");
        String action = login.text("event.action");
        HitList result = logs.findAll("test", LogCriteria.builder().sessionIds(List.of(session))
                .eventActions(List.of(action)).build(), 100);

        assertThat(ids(result.hits())).containsExactlyElementsOf(
                expectedIds(d -> session.equals(d.text("session.id")) && action.equals(d.text("event.action"))));
    }

    @Test
    void threadWindowOnOneHost() {
        Doc billing = EsFixtures.demoDocs().stream().filter(d -> "D".equals(d.text("log_format"))).findFirst().orElseThrow();
        Instant ts = Instant.parse(billing.text("@timestamp"));
        Instant from = ts.minusMillis(500);
        Instant to = ts.plusMillis(500);
        HitList result = logs.findAll("test", LogCriteria.builder().host(billing.text("host.name"))
                .thread(billing.text("process.thread.name")).anyOf(List.of(new LogCriteria.TimeRange(from, to))).build(), 1_000);

        assertThat(ids(result.hits())).contains(billing.id()).containsExactlyElementsOf(expectedIds(d ->
                billing.text("host.name").equals(d.text("host.name"))
                        && billing.text("process.thread.name").equals(d.text("process.thread.name"))
                        && !Instant.parse(d.text("@timestamp")).isBefore(from) && !Instant.parse(d.text("@timestamp")).isAfter(to)));
    }

    @Test
    void missingThreadAndRequestIdSelectLinesWithoutThem() {
        HitList result = logs.findAll("test", LogCriteria.builder().missingThread().missingRequestId().build(), 5_000);
        assertThat(ids(result.hits())).containsExactlyElementsOf(expectedIds(d ->
                d.text("process.thread.name") == null && d.text("http.request.id") == null));
    }

    @Test
    void latestMatchingLineBeforeAnInstant() {
        Doc login = EsFixtures.demoDocs().stream().filter(d -> d.text("session.id") != null
                && "auth-service".equals(d.text("service.name")) && d.text("user.id") != null).reduce((a, b) -> b).orElseThrow();
        Instant before = Instant.parse(login.text("@timestamp")).plusSeconds(1);
        var latest = logs.findLatest("test", LogCriteria.builder().userId(login.text("user.id"))
                .eventActions(List.of(login.text("event.action"))).to(before).build());

        assertThat(latest).isPresent();
        assertThat(latest.get().userId()).isEqualTo(login.text("user.id"));
        assertThat(latest.get().timestamp()).isBeforeOrEqualTo(before);
    }

    @Test
    void termsAggregationAndCompositePaging() {
        Set<String> allRefs = EsFixtures.demoDocs().stream().flatMap(d -> d.list("flow.refs").stream()).collect(Collectors.toSet());
        Map<String, Long> counts = logs.terms("test", LogCriteria.builder().hasFlowRef(true).build(), LogFields.FLOW_REFS, 1_000);
        assertThat(counts.keySet()).isEqualTo(allRefs);

        Set<String> paged = new HashSet<>();
        String after = null;
        int pages = 0;
        do {
            TermsPage page = logs.termsPage("test", LogCriteria.builder().build(), LogFields.FLOW_REFS, after, 3);
            paged.addAll(page.values());
            after = page.afterKey();
            pages++;
        } while (after != null && pages < 100);
        assertThat(paged).isEqualTo(allRefs);
    }

    @Test
    void freeTextPageHasCappedTotalAndHighlight() {
        HitPage page = logs.searchPage("test", LogCriteria.builder().text("SQLTimeoutException").build(), 0, 25, true);

        long expected = EsFixtures.demoDocs().stream().filter(d -> {
            String body = d.text("message_body");
            return body != null && body.contains("SQLTimeoutException");
        }).count();
        assertThat(page.total()).isGreaterThanOrEqualTo(expected);
        assertThat(page.totalIsLowerBound()).isFalse();
        assertThat(page.highlights().values()).anyMatch(h -> h.contains("<em>"));
    }

    @Test
    void countMaxIngestedAtAndGetByIds() {
        String ref = aFlowRef();
        LogCriteria byRef = LogCriteria.builder().flowRef(ref).build();
        List<String> expected = expectedIds(d -> d.list("flow.refs").contains(ref));

        assertThat(logs.count("test", byRef)).isEqualTo(expected.size());
        assertThat(logs.maxIngestedAt("test", byRef)).contains(Instant.parse("2026-05-16T00:00:00Z"));
        List<String> wanted = List.of(expected.get(0), expected.get(expected.size() - 1), "no-such-document");
        assertThat(ids(logs.getByIds("test", wanted))).containsExactly(expected.get(0), expected.get(expected.size() - 1));
    }

    @Test
    void capsAreEnforced() {
        HitList capped = logs.findAll("test", LogCriteria.builder().build(), 5);
        assertThat(capped.hits()).hasSize(5);
        assertThat(capped.truncated()).isTrue();

        HitPage big = logs.searchPage("test", LogCriteria.builder().build(), 0, 50_000, false);
        assertThat(big.hits()).hasSizeLessThanOrEqualTo(1_000);
        assertThatThrownBy(() -> logs.searchPage("test", LogCriteria.builder().build(), 20, 1_000, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRequestHasASizeCapAndATimeout() {
        var request = logs.findAllRequest(LogCriteria.builder().flowRef("x").build(), 50_000, null);
        assertThat(request.size()).isEqualTo(1_000);
        assertThat(request.timeout()).isEqualTo("10s");
        assertThat(request.sort()).hasSize(3);
    }

    @Test
    void healthIndicatorReportsTheCluster() {
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void unreachableElasticsearchFailsFastWithEsUnavailable() {
        var props = new LogSearchProperties("http://127.0.0.1:1", "logs-flow-*", "", "", Duration.ofSeconds(2), 1000, 10_000, 5_000);
        var config = new EsClientConfig();
        try (var rest = config.elasticsearchRestClient(props)) {
            var dead = new LogQueryService(config.elasticsearchClient(rest), props, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            long start = System.nanoTime();
            assertThatThrownBy(() -> dead.count("test", LogCriteria.builder().build())).isInstanceOf(EsUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
            assertThat(new EsHealthIndicator(config.elasticsearchClient(rest)).health().getStatus()).isEqualTo(Status.DOWN);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
