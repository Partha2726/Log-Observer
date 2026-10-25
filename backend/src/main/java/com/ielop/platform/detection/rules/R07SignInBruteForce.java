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

/** R07: at least {@code failedLoginThreshold} failed sign-ins for one user within the window (10 minutes). */
@Component
public class R07SignInBruteForce implements AnomalyRule {
    public String id() { return "R07"; }
    public String name() { return "Sign-in brute force"; }
    public Severity severity() { return Severity.HIGH; }
    public Scope scope() { return Scope.SCAN; }

    public List<Finding> evaluate(RuleInput in) {
        var rules = in.context().rules();
        Duration window = Duration.ofMinutes(rules.getFailedLoginWindowMinutes());
        Map<String, List<LogHit>> byUser = in.context()
                .linesWithActions(rules.getFailedLoginActions(), in.from(), in.to(), rules.getScanLineCap()).stream()
                .filter(h -> h.userId() != null)
                .collect(Collectors.groupingBy(LogHit::userId, TreeMap::new, Collectors.toList()));
        List<Finding> out = new ArrayList<>();
        byUser.forEach((user, lines) -> {
            lines.sort(ScanRules.BY_TIME);
            for (List<LogHit> burst : ScanRules.bursts(lines, window, b -> b.size() >= rules.getFailedLoginThreshold())) {
                var start = ScanRules.floor(burst.get(0).timestamp(), window);
                List<String> ips = burst.stream().map(LogHit::clientIp).filter(Objects::nonNull).distinct().sorted().toList();
                out.add(new Finding(id(), severity(), "R07:" + user + ":" + start, null, null, user,
                        ips.size() == 1 ? ips.get(0) : null, burst.get(0).timestamp(), burst.get(burst.size() - 1).timestamp(),
                        Map.of("failures", burst.size(), "clientIps", ips, "windowMinutes", rules.getFailedLoginWindowMinutes()),
                        ScanRules.oldest(burst, rules.getEvidenceSample())));
            }
        });
        return out;
    }
}
