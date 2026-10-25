package com.ielop.platform.logsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.util.NamedValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.ielop.platform.logsearch.LogFields.*;

/**
 * Typed, capped queries over the parsed log events (spec sections 7, 8.2 and 8.10).
 *
 * <p>Every search sets a size no larger than {@code maxPageSize}, an Elasticsearch
 * timeout, and a total-hits cap; a timed-out or failed search throws
 * {@link EsUnavailableException} instead of returning partial results.</p>
 */
@Service
public class LogQueryService {

    /** Deterministic order: never rely on Elasticsearch's own hit order (section 7.1 step 12). */
    static final List<String> ORDER_FIELDS = List.of(TIMESTAMP, FILE_PATH, OFFSET);

    private final ElasticsearchClient client;
    private final LogSearchProperties props;
    private final MeterRegistry meters;

    public LogQueryService(ElasticsearchClient client, LogSearchProperties props, MeterRegistry meters) {
        this.client = client;
        this.props = props;
        this.meters = meters;
    }

    public LogSearchProperties properties() {
        return props;
    }

    /** Every hit matching {@code criteria}, up to {@code cap}, paging with {@code search_after}. */
    public HitList findAll(String name, LogCriteria criteria, int cap) {
        List<LogHit> hits = new ArrayList<>();
        List<FieldValue> after = null;
        boolean truncated = false;
        while (true) {
            int size = Math.min(props.maxPageSize(), cap - hits.size() + 1);
            SearchRequest request = findAllRequest(criteria, size, after);
            SearchResponse<Map> response = execute(name, request);
            List<Hit<Map>> page = response.hits().hits();
            for (Hit<Map> hit : page) {
                if (hits.size() == cap) {
                    truncated = true;
                    break;
                }
                hits.add(toHit(hit));
            }
            if (truncated || page.size() < size) {
                break;
            }
            after = page.get(page.size() - 1).sort();
        }
        return new HitList(List.copyOf(hits), truncated);
    }

    SearchRequest findAllRequest(LogCriteria criteria, int size, List<FieldValue> after) {
        return SearchRequest.of(s -> {
            base(s, criteria).size(cap(size)).sort(order(SortOrder.Asc)).trackTotalHits(t -> t.enabled(false));
            if (after != null) {
                s.searchAfter(after);
            }
            return s;
        });
    }

    /** One page, newest first, with a capped total and optional message highlights. */
    public HitPage searchPage(String name, LogCriteria criteria, int page, int size, boolean highlight) {
        int pageSize = cap(size);
        long from = (long) page * pageSize;
        if (from + pageSize > props.totalHitsCap()) {
            throw new IllegalArgumentException("Results beyond the first " + props.totalHitsCap() + " are not available; narrow the filters");
        }
        SearchRequest request = SearchRequest.of(s -> {
            base(s, criteria).from((int) from).size(pageSize).sort(order(SortOrder.Desc))
                    .trackTotalHits(t -> t.count(props.totalHitsCap()));
            if (highlight && criteria.text != null) {
                s.highlight(h -> h.fields(NamedValue.of(MESSAGE_BODY,
                        HighlightField.of(f -> f.numberOfFragments(1).fragmentSize(240)))));
            }
            return s;
        });
        SearchResponse<Map> response = execute(name, request);
        List<LogHit> hits = response.hits().hits().stream().map(LogQueryService::toHit).toList();
        Map<String, String> highlights = new LinkedHashMap<>();
        for (Hit<Map> hit : response.hits().hits()) {
            List<String> fragments = hit.highlight().get(MESSAGE_BODY);
            if (fragments != null && !fragments.isEmpty()) {
                highlights.put(hit.id(), fragments.get(0));
            }
        }
        var total = response.hits().total();
        long value = total == null ? hits.size() : total.value();
        boolean lowerBound = total != null && total.relation() != co.elastic.clients.elasticsearch.core.search.TotalHitsRelation.Eq;
        return new HitPage(hits, value, lowerBound, highlights);
    }

    /** The latest hit matching {@code criteria}. */
    public Optional<LogHit> findLatest(String name, LogCriteria criteria) {
        SearchRequest request = SearchRequest.of(s -> base(s, criteria).size(1).sort(order(SortOrder.Desc))
                .trackTotalHits(t -> t.enabled(false)));
        return execute(name, request).hits().hits().stream().findFirst().map(LogQueryService::toHit);
    }

    /** Distinct values of a keyword field with their document counts, most frequent first (ties by value). */
    public Map<String, Long> terms(String name, LogCriteria criteria, String field, int size) {
        SearchRequest request = SearchRequest.of(s -> base(s, criteria).size(0).trackTotalHits(t -> t.enabled(false))
                .aggregations("values", a -> a.terms(t -> t.field(field).size(cap(size)))));
        Aggregate agg = execute(name, request).aggregations().get("values");
        Map<String, Long> out = new LinkedHashMap<>();
        for (StringTermsBucket b : agg.sterms().buckets().array()) {
            out.put(b.key().stringValue(), b.docCount());
        }
        return out;
    }

    /** Pages through every distinct value of a keyword field in value order (composite aggregation). */
    public TermsPage termsPage(String name, LogCriteria criteria, String field, String afterKey, int size) {
        SearchRequest request = SearchRequest.of(s -> base(s, criteria).size(0).trackTotalHits(t -> t.enabled(false))
                .aggregations("values", a -> a.composite(c -> {
                    c.size(cap(size)).sources(NamedValue.of("v",
                            CompositeAggregationSource.of(src -> src.terms(t -> t.field(field)))));
                    if (afterKey != null) {
                        c.after(Map.of("v", FieldValue.of(afterKey)));
                    }
                    return c;
                })));
        Aggregate agg = execute(name, request).aggregations().get("values");
        List<String> values = new ArrayList<>();
        for (CompositeBucket b : agg.composite().buckets().array()) {
            values.add(b.key().get("v").stringValue());
        }
        Map<String, FieldValue> next = agg.composite().afterKey();
        String nextKey = values.size() < cap(size) || next == null || next.get("v") == null ? null : next.get("v").stringValue();
        return new TermsPage(values, nextKey);
    }

    /** Number of matching documents (exact). */
    public long count(String name, LogCriteria criteria) {
        CountRequest request = CountRequest.of(c -> c.index(props.index()).ignoreUnavailable(true).allowNoIndices(true)
                .query(query(criteria)));
        return timed(name, () -> client.count(request)).count();
    }

    /** Latest {@code ingested_at} of the matching documents (staleness check, section 7.2). */
    public Optional<Instant> maxIngestedAt(String name, LogCriteria criteria) {
        SearchRequest request = SearchRequest.of(s -> base(s, criteria).size(0).trackTotalHits(t -> t.enabled(false))
                .aggregations("max", a -> a.max(m -> m.field(INGESTED_AT))));
        Double value = execute(name, request).aggregations().get("max").max().value();
        return value == null || value.isNaN() || value.isInfinite() ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(value.longValue()));
    }

    /** Documents by ID, in the deterministic order; missing IDs (for example expired by ILM) are simply absent. */
    public List<LogHit> getByIds(String name, Collection<String> ids) {
        List<String> all = new ArrayList<>(ids);
        List<LogHit> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += props.maxPageSize()) {
            List<String> chunk = all.subList(i, Math.min(all.size(), i + props.maxPageSize()));
            out.addAll(findAll(name, LogCriteria.builder().ids(chunk).build(), chunk.size()).hits());
        }
        return out;
    }

    // ------------------------------------------------------------------ building

    private SearchRequest.Builder base(SearchRequest.Builder s, LogCriteria criteria) {
        return s.index(props.index())
                .ignoreUnavailable(true)
                .allowNoIndices(true)
                .timeout(props.timeout().toSeconds() + "s")
                .query(query(criteria));
    }

    private int cap(int size) {
        return Math.max(0, Math.min(size, props.maxPageSize()));
    }

    private static List<SortOptions> order(SortOrder direction) {
        return ORDER_FIELDS.stream()
                .map(f -> SortOptions.of(s -> s.field(fs -> fs.field(f).order(direction).unmappedType(
                        f.equals(TIMESTAMP) ? co.elastic.clients.elasticsearch._types.mapping.FieldType.Date
                                : f.equals(OFFSET) ? co.elastic.clients.elasticsearch._types.mapping.FieldType.Long
                                : co.elastic.clients.elasticsearch._types.mapping.FieldType.Keyword))))
                .toList();
    }

    static Query query(LogCriteria c) {
        BoolQuery.Builder b = new BoolQuery.Builder();
        terms(b, FLOW_REFS, c.flowRefs);
        terms(b, REQUEST_ID, c.requestIds);
        terms(b, SESSION_ID, c.sessionIds);
        terms(b, USER_ID, c.userIds);
        terms(b, EVENT_ACTION, c.eventActions);
        terms(b, SERVICE, c.services);
        terms(b, HOST, c.hosts);
        terms(b, THREAD, c.threads);
        terms(b, LEVEL, c.levels);
        terms(b, CLIENT_IP, c.clientIps);
        terms(b, USER_AGENT, c.userAgents);
        terms(b, TAGS, c.tags);
        terms(b, TRACE_ID, c.traceIds);
        if (!c.httpStatuses.isEmpty()) {
            List<FieldValue> statuses = c.httpStatuses.stream().sorted().map(v -> FieldValue.of((long) v)).toList();
            b.filter(q -> q.terms(t -> t.field(HTTP_STATUS).terms(tv -> tv.value(statuses))));
        }
        if (c.hasHttpStatus) {
            b.filter(q -> q.exists(e -> e.field(HTTP_STATUS)));
        }
        if (!c.ids.isEmpty()) {
            b.filter(q -> q.ids(i -> i.values(List.copyOf(c.ids))));
        }
        if (c.from != null || c.to != null) {
            b.filter(range(c.from, c.to));
        }
        if (!c.anyOfRanges.isEmpty()) {
            b.filter(q -> q.bool(any -> {
                c.anyOfRanges.forEach(r -> any.should(range(r.from(), r.to())));
                return any.minimumShouldMatch("1");
            }));
        }
        if (!c.anyOfMatches.isEmpty()) {
            b.filter(q -> q.bool(any -> {
                for (FieldMatch m : c.anyOfMatches) {
                    any.should(s -> s.bool(one -> {
                        one.filter(f -> f.term(t -> t.field(SERVICE).value(m.service())));
                        if (m.httpMethod() != null) {
                            one.filter(f -> f.term(t -> t.field(HTTP_METHOD).value(m.httpMethod())));
                        }
                        if (m.urlPathRegexp() != null) {
                            one.filter(f -> f.regexp(r -> r.field(URL_PATH).value(m.urlPathRegexp())));
                        }
                        if (m.eventActions() != null && !m.eventActions().isEmpty()) {
                            List<FieldValue> actions = m.eventActions().stream().sorted().map(FieldValue::of).toList();
                            one.filter(f -> f.terms(t -> t.field(EVENT_ACTION).terms(tv -> tv.value(actions))));
                        }
                        return one;
                    }));
                }
                return any.minimumShouldMatch("1");
            }));
        }
        if (c.text != null) {
            b.must(q -> q.simpleQueryString(s -> s.query(c.text).fields(MESSAGE_BODY, MESSAGE)
                    .defaultOperator(Operator.And).lenient(true)));
        }
        if (c.hasFlowRef != null) {
            Query exists = Query.of(q -> q.exists(e -> e.field(FLOW_REFS)));
            if (c.hasFlowRef) {
                b.filter(exists);
            } else {
                b.mustNot(exists);
            }
        }
        if (c.missingThread) {
            b.mustNot(q -> q.exists(e -> e.field(THREAD)));
        }
        if (c.missingRequestId) {
            b.mustNot(q -> q.exists(e -> e.field(REQUEST_ID)));
        }
        return Query.of(q -> q.bool(b.build()));
    }

    private static void terms(BoolQuery.Builder b, String field, Set<String> values) {
        if (!values.isEmpty()) {
            List<FieldValue> fv = values.stream().sorted().map(FieldValue::of).toList();
            b.filter(q -> q.terms(t -> t.field(field).terms(tv -> tv.value(fv))));
        }
    }

    private static Query range(Instant from, Instant to) {
        return Query.of(q -> q.range(r -> r.date(d -> {
            d.field(TIMESTAMP);
            if (from != null) {
                d.gte(from.toString());
            }
            if (to != null) {
                d.lte(to.toString());
            }
            return d;
        })));
    }

    // ----------------------------------------------------------------- executing

    @SuppressWarnings("rawtypes")
    private SearchResponse<Map> execute(String name, SearchRequest request) {
        SearchResponse<Map> response = timed(name, () -> client.search(request, Map.class));
        if (Boolean.TRUE.equals(response.timedOut())) {
            throw new EsUnavailableException("Elasticsearch query '" + name + "' timed out", null);
        }
        return response;
    }

    private <T> T timed(String name, EsCall<T> call) {
        Timer.Sample sample = Timer.start(meters);
        try {
            return call.run();
        } catch (ElasticsearchException e) {
            if (e.status() == 400) {
                throw new IllegalArgumentException("Invalid log query: " + e.getMessage(), e);
            }
            throw new EsUnavailableException("Elasticsearch query '" + name + "' failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new EsUnavailableException("Elasticsearch is unavailable (" + name + "): " + e.getMessage(), e);
        } finally {
            sample.stop(meters.timer("es.query.duration", "query", name));
        }
    }

    @FunctionalInterface
    private interface EsCall<T> {
        T run() throws IOException;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static LogHit toHit(Hit<Map> hit) {
        return LogHitMapper.map(hit.id(), hit.index(), (Map<String, Object>) hit.source());
    }
}
