package com.lokiscale.bifrost.core;

import java.util.Objects;

public final class DefaultBifrostExceptionTransformer implements BifrostExceptionTransformer {

    @Override
    public String transform(Throwable throwable) {
        Throwable relevant = rootCauseOf(throwable);
        String hint = relevant.getMessage();
        if (hint == null || hint.isBlank()) {
            hint = "No additional details provided.";
        }
        return "ERROR: " + relevant.getClass().getSimpleName() + ". HINT: " + hint;
    }

    private Throwable rootCauseOf(Throwable throwable) {
        Throwable current = Objects.requireNonNull(throwable, "throwable must not be null");
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
