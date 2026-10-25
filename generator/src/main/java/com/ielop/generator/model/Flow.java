package com.ielop.generator.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One planned flow and its ground-truth labels (spec section 5.6). Stage
 * statuses use the vocabulary of section 2.5.
 */
public final class Flow {

    private final int index;
    private final String flowRef;
    private final Scenario scenario;
    private final long startMillis;
    private final User requester;
    private User approver;
    private User executor;
    private final BigDecimal amount;
    private final String currency;
    private final String vendorAccount;
    private final String costCentre;
    private final Map<String, String> stageStatus = new LinkedHashMap<>();
    private String failingStage;
    private String failingComponent;

    public Flow(int index, String flowRef, Scenario scenario, long startMillis, User requester, BigDecimal amount,
                String currency, String vendorAccount, String costCentre) {
        this.index = index;
        this.flowRef = flowRef;
        this.scenario = scenario;
        this.startMillis = startMillis;
        this.requester = requester;
        this.amount = amount;
        this.currency = currency;
        this.vendorAccount = vendorAccount;
        this.costCentre = costCentre;
    }

    /** DUPLICATE_SUBMIT: the same request submitted again under a new flowRef. */
    public Flow duplicate(String ref) {
        Flow d = new Flow(index, ref, scenario, startMillis, requester, amount, currency, vendorAccount, costCentre);
        return d;
    }

    public int index() {
        return index;
    }

    public String flowRef() {
        return flowRef;
    }

    public Scenario scenario() {
        return scenario;
    }

    public long startMillis() {
        return startMillis;
    }

    public User requester() {
        return requester;
    }

    public User approver() {
        return approver;
    }

    public void approver(User approver) {
        this.approver = approver;
    }

    public User executor() {
        return executor;
    }

    public void executor(User executor) {
        this.executor = executor;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public String vendorAccount() {
        return vendorAccount;
    }

    public String costCentre() {
        return costCentre;
    }

    public Map<String, String> stageStatus() {
        return stageStatus;
    }

    public void status(String stage, String status) {
        stageStatus.put(stage, status);
    }

    public String failingStage() {
        return failingStage;
    }

    public String failingComponent() {
        return failingComponent;
    }

    public void failure(String stage, String component) {
        this.failingStage = stage;
        this.failingComponent = component;
    }

    /** Overall status by the priority of section 2.5: COMPLETED, FAILED, REJECTED, INCOMPLETE. */
    public String overallStatus(String finalStage) {
        if (stageStatus.containsValue("FAILED")) {
            return "FAILED";
        }
        if ("COMPLETED".equals(stageStatus.get(finalStage))) {
            return "COMPLETED";
        }
        if (stageStatus.containsValue("REJECTED")) {
            return "REJECTED";
        }
        return "INCOMPLETE";
    }
}
