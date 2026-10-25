package com.ielop.platform.logsearch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Filters for one log query. Every set is an "any of" term filter on an exact
 * keyword field; all filters are combined with AND. Instances are immutable;
 * use {@link #builder()}.
 */
public final class LogCriteria {

    /** A closed time interval. */
    public record TimeRange(Instant from, Instant to) {
    }

    final Set<String> flowRefs;
    final Set<String> requestIds;
    final Set<String> sessionIds;
    final Set<String> userIds;
    final Set<String> eventActions;
    final Set<String> services;
    final Set<String> hosts;
    final Set<String> threads;
    final Set<String> levels;
    final Set<String> clientIps;
    final Set<String> ids;
    final Set<String> userAgents;
    final Set<Integer> httpStatuses;
    final Set<String> tags;
    final Set<String> traceIds;
    final boolean hasHttpStatus;
    final Instant from;
    final Instant to;
    final List<TimeRange> anyOfRanges;
    final List<FieldMatch> anyOfMatches;
    final String text;
    final Boolean hasFlowRef;
    final boolean missingThread;
    final boolean missingRequestId;

    private LogCriteria(Builder b) {
        this.flowRefs = Set.copyOf(b.flowRefs);
        this.requestIds = Set.copyOf(b.requestIds);
        this.sessionIds = Set.copyOf(b.sessionIds);
        this.userIds = Set.copyOf(b.userIds);
        this.eventActions = Set.copyOf(b.eventActions);
        this.services = Set.copyOf(b.services);
        this.hosts = Set.copyOf(b.hosts);
        this.threads = Set.copyOf(b.threads);
        this.levels = Set.copyOf(b.levels);
        this.clientIps = Set.copyOf(b.clientIps);
        this.ids = Set.copyOf(b.ids);
        this.userAgents = Set.copyOf(b.userAgents);
        this.httpStatuses = Set.copyOf(b.httpStatuses);
        this.tags = Set.copyOf(b.tags);
        this.traceIds = Set.copyOf(b.traceIds);
        this.hasHttpStatus = b.hasHttpStatus;
        this.from = b.from;
        this.to = b.to;
        this.anyOfRanges = List.copyOf(b.anyOfRanges);
        this.anyOfMatches = List.copyOf(b.anyOfMatches);
        this.text = b.text;
        this.hasFlowRef = b.hasFlowRef;
        this.missingThread = b.missingThread;
        this.missingRequestId = b.missingRequestId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Set<String> flowRefs = new LinkedHashSet<>();
        private final Set<String> requestIds = new LinkedHashSet<>();
        private final Set<String> sessionIds = new LinkedHashSet<>();
        private final Set<String> userIds = new LinkedHashSet<>();
        private final Set<String> eventActions = new LinkedHashSet<>();
        private final Set<String> services = new LinkedHashSet<>();
        private final Set<String> hosts = new LinkedHashSet<>();
        private final Set<String> threads = new LinkedHashSet<>();
        private final Set<String> levels = new LinkedHashSet<>();
        private final Set<String> clientIps = new LinkedHashSet<>();
        private final Set<String> ids = new LinkedHashSet<>();
        private final Set<String> userAgents = new LinkedHashSet<>();
        private final Set<Integer> httpStatuses = new LinkedHashSet<>();
        private final Set<String> tags = new LinkedHashSet<>();
        private final Set<String> traceIds = new LinkedHashSet<>();
        private boolean hasHttpStatus;
        private final List<TimeRange> anyOfRanges = new ArrayList<>();
        private final List<FieldMatch> anyOfMatches = new ArrayList<>();
        private Instant from;
        private Instant to;
        private String text;
        private Boolean hasFlowRef;
        private boolean missingThread;
        private boolean missingRequestId;

        public Builder flowRefs(Collection<String> v) { flowRefs.addAll(v); return this; }
        public Builder flowRef(String v) { flowRefs.add(v); return this; }
        public Builder requestIds(Collection<String> v) { requestIds.addAll(v); return this; }
        public Builder requestId(String v) { requestIds.add(v); return this; }
        public Builder sessionIds(Collection<String> v) { sessionIds.addAll(v); return this; }
        public Builder userId(String v) { userIds.add(v); return this; }
        public Builder userIds(Collection<String> v) { userIds.addAll(v); return this; }
        public Builder eventActions(Collection<String> v) { eventActions.addAll(v); return this; }
        public Builder services(Collection<String> v) { services.addAll(v); return this; }
        public Builder service(String v) { services.add(v); return this; }
        public Builder host(String v) { hosts.add(v); return this; }
        public Builder hosts(Collection<String> v) { hosts.addAll(v); return this; }
        public Builder thread(String v) { threads.add(v); return this; }
        public Builder levels(Collection<String> v) { levels.addAll(v); return this; }
        public Builder clientIp(String v) { clientIps.add(v); return this; }
        public Builder ids(Collection<String> v) { ids.addAll(v); return this; }
        public Builder clientIps(Collection<String> v) { clientIps.addAll(v); return this; }
        /** Exact user agent strings. */
        public Builder userAgents(Collection<String> v) { userAgents.addAll(v); return this; }
        public Builder httpStatuses(Collection<Integer> v) { httpStatuses.addAll(v); return this; }
        /** Lines carrying any of these pipeline tags (for example parse failures). */
        public Builder tags(Collection<String> v) { tags.addAll(v); return this; }
        public Builder traceIds(Collection<String> v) { traceIds.addAll(v); return this; }
        /** Only lines with an HTTP response status (gateway access lines). */
        public Builder hasHttpStatus() { hasHttpStatus = true; return this; }
        public Builder from(Instant v) { from = v; return this; }
        public Builder to(Instant v) { to = v; return this; }
        public Builder between(Instant f, Instant t) { from = f; to = t; return this; }
        /** Matches lines in any of the given intervals (OR). */
        public Builder anyOf(Collection<TimeRange> ranges) { anyOfRanges.addAll(ranges); return this; }
        /** Matches lines that satisfy any of the conditions (OR); an empty list adds no filter. */
        public Builder anyOfMatches(Collection<FieldMatch> matches) { anyOfMatches.addAll(matches); return this; }
        /** Free text over the message, Elasticsearch {@code simple_query_string} syntax. */
        public Builder text(String v) { text = v == null || v.isBlank() ? null : v; return this; }
        public Builder hasFlowRef(boolean v) { hasFlowRef = v; return this; }
        public Builder missingThread() { missingThread = true; return this; }
        public Builder missingRequestId() { missingRequestId = true; return this; }

        public LogCriteria build() {
            return new LogCriteria(this);
        }
    }
}
