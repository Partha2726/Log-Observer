package com.ielop.platform.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Test gateway: answers from a script of raw outputs when one is queued, otherwise like {@link FakeAiGateway}. */
public class ScriptedAiGateway implements AiGateway {

    private final FakeAiGateway fake;
    final Deque<String> script = new ArrayDeque<>();
    final Deque<String> chatScript = new ArrayDeque<>();
    final List<SummaryRequest> requests = new ArrayList<>();

    public ScriptedAiGateway(ObjectMapper json) {
        this.fake = new FakeAiGateway(json);
    }

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public String model() {
        return FakeAiGateway.MODEL;
    }

    @Override
    public synchronized SummaryResult summarize(SummaryRequest request) {
        requests.add(request);
        String next = script.poll();
        return next == null ? fake.summarize(request) : new SummaryResult(next, new Usage(100, 20), "fake", FakeAiGateway.MODEL);
    }

    @Override
    public synchronized ChatTurnResult chat(ChatRequest request) {
        String next = chatScript.poll();
        return next == null ? fake.chat(request) : new ChatTurnResult(next, new Usage(50, 10), "fake", FakeAiGateway.MODEL);
    }

    synchronized void reset() {
        script.clear();
        chatScript.clear();
        requests.clear();
    }
}
