package com.ielop.generator.model;

import java.math.BigDecimal;

/**
 * An observed Acme Cloud employee (spec section 8.9: data, never an account).
 *
 * @param approvalLimit null when the user cannot approve (no {@code requests:approve} permission)
 */
public record User(String id, String name, String email, String homeIp, String userAgent, boolean requester,
                   BigDecimal approvalLimit) {

    public boolean canApprove() {
        return approvalLimit != null;
    }

    public String rolesLabel() {
        if (requester && canApprove()) {
            return "both";
        }
        return canApprove() ? "approver" : "requester";
    }
}
