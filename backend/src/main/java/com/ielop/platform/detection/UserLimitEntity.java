package com.ielop.platform.detection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** An observed user's approval limit (table {@code user_limit}, feeds R10). Observed users are data, never accounts (section 8.9). */
@Entity
@Table(name = "user_limit")
public class UserLimitEntity {

    @Id
    @Column(name = "user_id", length = 64)
    private String userId;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "roles", nullable = false, columnDefinition = "text[]")
    private String[] roles;
    @Column(name = "limit_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal limitAmount;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    protected UserLimitEntity() {
    }

    public UserLimitEntity(String userId, String[] roles, BigDecimal limitAmount, String currency, Instant importedAt) {
        this.userId = userId;
        this.roles = roles;
        this.limitAmount = limitAmount;
        this.currency = currency;
        this.importedAt = importedAt;
    }

    public String getUserId() { return userId; }
    public String[] getRoles() { return roles; }
    public BigDecimal getLimitAmount() { return limitAmount; }
    public String getCurrency() { return currency; }
    public Instant getImportedAt() { return importedAt; }
}
