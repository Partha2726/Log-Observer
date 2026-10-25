package com.ielop.platform.trace.repo;

import com.ielop.platform.trace.entity.TraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TraceRepository extends JpaRepository<TraceEntity, Long> {

    Optional<TraceEntity> findByFlowRef(String flowRef);
}
