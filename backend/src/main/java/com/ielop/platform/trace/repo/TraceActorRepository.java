package com.ielop.platform.trace.repo;

import com.ielop.platform.trace.entity.TraceActorEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TraceActorRepository extends JpaRepository<TraceActorEntity, TraceActorEntity.Key> {

    List<TraceActorEntity> findByTraceIdOrderByRole(Long traceId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TraceActorEntity e where e.traceId = :traceId")
    int deleteByTraceId(@Param("traceId") Long traceId);
}
