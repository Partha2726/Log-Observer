package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.Severity;
import com.ielop.platform.logsearch.LogHit;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * R15: one client IP with at least {@code scannerRequestThreshold} HTTP requests within 5 minutes of which at
 * least {@code scannerRejectedShare} were rejected (401, 403, 404), or any request whose user agent contains a
 * known scanner name. Candidate IPs come from an aggregation, so ordinary users' lines are never read in bulk.
 */
@Component
public class R15ScannerTraffic implements AnomalyRule {
    public String id() { return "R15"; }
    public String name() { return "Scanner traffic"; }
    public Severity severity() { return Severity.MEDIUM; }
    public Scope scope() { return Scope.SCAN; }

    public List<Finding> evaluate(RuleInput in) {
        var ctx = in.context();
        var rules = ctx.rules();
        Duration window = Duration.ofMinutes(rules.getScannerWindowMinutes());
        Set<Integer> rejected = Set.copyOf(rules.getScannerRejectedStatuses());
        Map<String, Finding> out = new LinkedHashMap<>();

        // Volume: an IP needs at least threshold * share rejected requests in the range to qualify at all.
        long minRejected = (long) Math.ceil(rules.getScannerRequestThreshold() * rules.getScannerRejectedShare());
        List<String> candidates = ctx.ipsWithStatuses(rejected, in.from(), in.to(), 1000).entrySet().stream()
                .filter(e -> e.getValue() >= minRejected).map(Map.Entry::getKey).sorted().toList();
        if (!candidates.isEmpty()) {
            Map<String, List<LogHit>> byIp = group(ctx.httpLinesOfIps(candidates, in.from(), in.to(), rules.getScanLineCap()));
            byIp.forEach((ip, lines) -> {
                for (List<LogHit> burst : ScanRules.bursts(lines, window, b -> b.size() >= rules.getScannerRequestThreshold()
                        && share(b, rejected) >= rules.getScannerRejectedShare())) {
                    add(out, ip, burst, window, rejected, rules.getEvidenceSample(), "volume");
                }
            });
        }

        // User agent: any request from a known scanner, grouped per IP and 5-minute window.
        List<String> agents = ctx.userAgents(in.from(), in.to(), 1000).keySet().stream()
                .filter(a -> rules.getScannerUserAgents().stream().anyMatch(k -> a.toLowerCase(Locale.ROOT).contains(k.toLowerCase(Locale.ROOT))))
                .sorted().toList();
        if (!agents.isEmpty()) {
            Map<String, List<LogHit>> byIp = group(ctx.httpLinesWithAgents(agents, in.from(), in.to(), rules.getScanLineCap()));
            byIp.forEach((ip, lines) -> {
                Map<Instant, List<LogHit>> byWindow = lines.stream()
                        .collect(Collectors.groupingBy(h -> ScanRules.floor(h.timestamp(), window), TreeMap::new, Collectors.toList()));
                byWindow.values().forEach(w -> add(out, ip, w, window, rejected, rules.getEvidenceSample(), "userAgent"));
            });
        }
        return List.copyOf(out.values());
    }

    private void add(Map<String, Finding> out, String ip, List<LogHit> lines, Duration window, Set<Integer> rejected, int sample,
                     String reason) {
        Instant start = ScanRules.floor(lines.get(0).timestamp(), window);
        String key = "R15:" + ip + ":" + start;
        if (out.containsKey(key)) {
            return;
        }
        List<String> agents = lines.stream().map(LogHit::userAgent).filter(a -> a != null).distinct().sorted().limit(5).toList();
        out.put(key, new Finding(id(), severity(), key, null, null, null, ip, lines.get(0).timestamp(),
                lines.get(lines.size() - 1).timestamp(),
                Map.of("requests", lines.size(), "rejectedShare", Math.round(share(lines, rejected) * 100) / 100.0,
                        "userAgents", agents, "reason", reason),
                ScanRules.newest(lines, sample)));
    }

    private static Map<String, List<LogHit>> group(List<LogHit> lines) {
        Map<String, List<LogHit>> byIp = lines.stream().filter(h -> h.clientIp() != null)
                .collect(Collectors.groupingBy(LogHit::clientIp, TreeMap::new, Collectors.toList()));
        byIp.values().forEach(l -> l.sort(ScanRules.BY_TIME));
        return byIp;
    }

    private static double share(List<LogHit> lines, Set<Integer> rejected) {
        long n = lines.stream().filter(h -> h.httpStatus() != null && rejected.contains(h.httpStatus())).count();
        return lines.isEmpty() ? 0 : (double) n / lines.size();
    }
}
