package com.ielop.platform.audit;

import com.ielop.platform.detection.AnomalyDtos;
import com.ielop.platform.detection.AnomalyReviewHistory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** An anomaly's review history (section 8.8) from its {@code ANOMALY_REVIEWED} audit rows. */
@Component
public class AuditReviewHistory implements AnomalyReviewHistory {

    private final AuditEventRepository events;

    public AuditReviewHistory(AuditEventRepository events) {
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnomalyDtos.Review> reviews(long anomalyId) {
        return events.findByActionAndTargetTypeAndTargetIdOrderByOccurredAtAscIdAsc("ANOMALY_REVIEWED", "anomaly", String.valueOf(anomalyId))
                .stream().map(e -> {
                    Map<String, Object> m = e.getMetadata() == null ? Map.of() : e.getMetadata();
                    return new AnomalyDtos.Review(e.getActor(), str(m.get("fromStatus")), str(m.get("toStatus")), str(m.get("note")),
                            e.getOccurredAt());
                }).toList();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
