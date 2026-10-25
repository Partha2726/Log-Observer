package com.ielop.platform.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The state of one assistant turn: who asks, how many tool calls are left, the tool calls made, and the
 * evidence IDs issued in the conversation so far (context lines and earlier tool results), to which new
 * tool results append. Only issued IDs survive as citations (AI-011).
 */
public final class ToolTurn {

    /** One issued evidence ID: the document it stands for, with its stage and time for display. */
    public record Issued(String docId, String stage, String ts) {
    }

    /** One tool call as the API reports it. */
    public record ToolCall(String name, String args, int resultCount, long durationMs, boolean refused) {
    }

    static final Pattern EV = Pattern.compile("ev(\\d+)");

    final String user;
    final UUID conversationId;
    final int maxCalls;
    final AtomicInteger calls = new AtomicInteger();
    final List<ToolCall> toolCalls = new ArrayList<>();
    final Map<String, Issued> issued;
    final Map<String, Issued> issuedThisTurn = new LinkedHashMap<>();
    private int next;

    public ToolTurn(String user, UUID conversationId, int maxCalls, Map<String, Issued> issued) {
        this.user = user;
        this.conversationId = conversationId;
        this.maxCalls = maxCalls;
        this.issued = new LinkedHashMap<>(issued);
        this.next = issued.keySet().stream().map(EV::matcher).filter(Matcher::matches).mapToInt(m -> Integer.parseInt(m.group(1))).max().orElse(0);
    }

    /** Issues the next evidence ID for a document, reusing the ID it already has in this conversation. */
    synchronized String issue(String docId, String stage, String ts) {
        for (Map.Entry<String, Issued> e : issued.entrySet()) {
            if (e.getValue().docId().equals(docId)) {
                return e.getKey();
            }
        }
        String id = "ev" + (++next);
        Issued i = new Issued(docId, stage, ts);
        issued.put(id, i);
        issuedThisTurn.put(id, i);
        return id;
    }

    synchronized void record(ToolCall call) {
        toolCalls.add(call);
    }

    public synchronized List<ToolCall> toolCalls() {
        return List.copyOf(toolCalls);
    }

    public synchronized Map<String, Issued> issued() {
        return Map.copyOf(issued);
    }

    public synchronized Map<String, Issued> issuedThisTurn() {
        return Map.copyOf(issuedThisTurn);
    }
}
