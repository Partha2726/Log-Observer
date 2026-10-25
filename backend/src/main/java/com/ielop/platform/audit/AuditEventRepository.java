package com.ielop.platform.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findByActionAndTargetTypeAndTargetIdOrderByOccurredAtAscIdAsc(String action, String targetType, String targetId);
}
