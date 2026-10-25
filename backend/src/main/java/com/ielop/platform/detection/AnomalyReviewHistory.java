package com.ielop.platform.detection;

import java.util.List;

/** Past reviews of an anomaly, oldest first; the audit package provides them once audit events are stored. */
public interface AnomalyReviewHistory {

    List<AnomalyDtos.Review> reviews(long anomalyId);
}
