package com.ielop.platform.flowdef;

import java.util.List;

/** A flow definition failed schema or semantic validation; the backend refuses to start with it. */
public class FlowDefinitionException extends RuntimeException {

    private final List<String> problems;

    public FlowDefinitionException(String source, List<String> problems) {
        super("Invalid flow definition " + source + ": " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
