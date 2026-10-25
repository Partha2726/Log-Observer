package com.ielop.platform.assistant;

/** Used when AI is off (kill switch, no key or no model): every call is 503 {@code AI_DISABLED}. */
final class DisabledAiGateway implements AiGateway {

    private final AiAvailability availability;

    DisabledAiGateway(AiAvailability availability) {
        this.availability = availability;
    }

    @Override
    public String provider() {
        return availability.provider();
    }

    @Override
    public String model() {
        return availability.model();
    }

    @Override
    public SummaryResult summarize(SummaryRequest request) {
        throw AiException.disabled(availability.reason());
    }

    @Override
    public ChatTurnResult chat(ChatRequest request) {
        throw AiException.disabled(availability.reason());
    }
}
