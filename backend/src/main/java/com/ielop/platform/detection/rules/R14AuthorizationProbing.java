package com.ielop.platform.detection.rules;

import com.ielop.platform.detection.AnomalyRule;
import com.ielop.platform.detection.Finding;
import com.ielop.platform.detection.RuleInput;
import com.ielop.platform.detection.Severity;
import com.ielop.platform.logsearch.LogHit;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** R14: at least {@code authzDeniedThreshold} permission denials for one user on at least 3 distinct resources within 10 minutes. */
@Component
public class R14AuthorizationProbing implements AnomalyRule {
    public String id() { return "R14"; }
    public String name() { return "Authorization probing"; }
    public Severity severity() { return Severity.HIGH; }
    public Scope scope() { return Scope.SCAN; }

    public List<Finding> evaluate(RuleInput in) {
        var rules = in.context().rules();
        Duration window = Duration.ofMinutes(rules.getAuthzWindowMinutes());
        Map<String, List<LogHit>> byUser = in.context()
                .linesWithActions(rules.getAuthzDeniedActions(), in.from(), in.to(), rules.getScanLineCap()).stream()
                .filter(h -> h.userId() != null)
                .collect(Collectors.groupingBy(LogHit::userId, TreeMap::new, Collectors.toList()));
        List<Finding> out = new ArrayList<>();
        byUser.forEach((user, lines) -> {
            lines.sort(ScanRules.BY_TIME);
            for (List<LogHit> burst : ScanRules.bursts(lines, window, b -> b.size() >= rules.getAuthzDeniedThreshold()
                    && resources(b).size() >= rules.getAuthzDistinctResources())) {
                var start = ScanRules.floor(burst.get(0).timestamp(), window);
                List<String> ips = burst.stream().map(LogHit::clientIp).filter(Objects::nonNull).distinct().sorted().toList();
                out.add(new Finding(id(), severity(), "R14:" + user + ":" + start, null, null, user,
                        ips.size() == 1 ? ips.get(0) : null, burst.get(0).timestamp(), burst.get(burst.size() - 1).timestamp(),
                        Map.of("denials", burst.size(), "resources", resources(burst)),
                        ScanRules.oldest(burst, rules.getEvidenceSample())));
            }
        });
        return out;
    }

    private static List<String> resources(List<LogHit> lines) {
        return lines.stream().map(LogHit::resource).filter(Objects::nonNull).distinct().sorted().toList();
    }
}
