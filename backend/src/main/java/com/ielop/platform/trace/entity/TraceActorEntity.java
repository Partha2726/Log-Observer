package com.ielop.platform.trace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** The user acting in one role of a trace (table {@code trace_actor}). */
@Entity
@Table(name = "trace_actor")
@IdClass(TraceActorEntity.Key.class)
public class TraceActorEntity {

    @Id
    @Column(name = "trace_id", nullable = false)
    private Long traceId;
    @Id
    @Column(name = "role", nullable = false, length = 32)
    private String role;
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    protected TraceActorEntity() {
    }

    public TraceActorEntity(Long traceId, String role, String userId) {
        this.traceId = traceId;
        this.role = role;
        this.userId = userId;
    }

    public Long getTraceId() { return traceId; }
    public String getRole() { return role; }
    public String getUserId() { return userId; }

    public static class Key implements Serializable {
        private Long traceId;
        private String role;

        public Key() {
        }

        public Key(Long traceId, String role) {
            this.traceId = traceId;
            this.role = role;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(traceId, k.traceId) && Objects.equals(role, k.role);
        }

        @Override
        public int hashCode() {
            return Objects.hash(traceId, role);
        }
    }
}
