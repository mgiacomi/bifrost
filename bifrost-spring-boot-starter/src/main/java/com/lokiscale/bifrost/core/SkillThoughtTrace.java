package com.lokiscale.bifrost.core;

import java.util.List;
import java.util.Objects;

public record SkillThoughtTrace(
        String route,
        List<SkillThought> thoughts) {

    public SkillThoughtTrace {
        route = requireNonBlank(route, "route");
        thoughts = thoughts == null ? List.of() : List.copyOf(thoughts);
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
