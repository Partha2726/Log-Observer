package com.ielop.platform.detection;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AnomalyRepository extends JpaRepository<AnomalyEntity, Long>, JpaSpecificationExecutor<AnomalyEntity> {

    List<AnomalyEntity> findByTraceIdOrderByIdAsc(Long traceId);

    @Query("""
            SELECT a FROM AnomalyEntity a WHERE a.userId = :user
               AND COALESCE(a.windowEnd, a.windowStart, a.detectedAt) >= :from AND COALESCE(a.windowStart, a.detectedAt) <= :to
             ORDER BY COALESCE(a.windowStart, a.detectedAt) DESC, a.id DESC""")
    List<AnomalyEntity> findForUser(@Param("user") String userId, @Param("from") Instant from, @Param("to") Instant to, Pageable page);
}
