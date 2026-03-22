package com.lokiscale.bifrost.runtime;

import com.lokiscale.bifrost.runtime.usage.GuardrailType;

public class BifrostQuotaExceededException extends RuntimeException {

    private final String sessionId;
    private final GuardrailType guardrailType;
    private final long limit;
    private final long observed;

    public BifrostQuotaExceededException(String sessionId, GuardrailType guardrailType, long limit, long observed) {
        super("Session '%s' exceeded %s quota: observed=%d, limit=%d"
                .formatted(sessionId, guardrailType, observed, limit));
        this.sessionId = sessionId;
        this.guardrailType = guardrailType;
        this.limit = limit;
        this.observed = observed;
    }

    public String getSessionId() {
        return sessionId;
    }

    public GuardrailType getGuardrailType() {
        return guardrailType;
    }

    public long getLimit() {
        return limit;
    }

    public long getObserved() {
        return observed;
    }
}
