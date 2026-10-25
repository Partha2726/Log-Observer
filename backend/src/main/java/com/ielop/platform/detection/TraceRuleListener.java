package com.ielop.platform.detection;

import com.ielop.platform.trace.TraceAssembledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs the trace-scoped rules after every assembly commits (section 4.5.3). A rule
 * failure is logged and never breaks the trace request.
 */
@Component
public class TraceRuleListener {

    private static final Logger log = LoggerFactory.getLogger(TraceRuleListener.class);

    private final RuleEngine engine;

    public TraceRuleListener(RuleEngine engine) {
        this.engine = engine;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAssembled(TraceAssembledEvent event) {
        try {
            engine.evaluateTrace(event.flowRef());
        } catch (RuntimeException e) {
            log.error("Trace rules failed for {}", event.flowRef(), e);
        }
    }
}
