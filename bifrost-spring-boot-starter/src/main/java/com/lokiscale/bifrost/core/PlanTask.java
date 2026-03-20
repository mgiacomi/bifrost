package com.lokiscale.bifrost.core;

import org.springframework.lang.Nullable;

import java.util.Objects;

public record PlanTask(
        String taskId,
        String title,
        PlanTaskStatus status,
        @Nullable String capabilityName,
        @Nullable String intent,
        java.util.List<String> dependsOn,
        java.util.List<String> expectedOutputs,
        boolean autoCompletable,
        @Nullable String note) {

    public PlanTask {
        taskId = requireNonBlank(taskId, "taskId");
        title = requireNonBlank(title, "title");
        status = Objects.requireNonNull(status, "status must not be null");
        capabilityName = normalizeNullable(capabilityName);
        intent = normalizeNullable(intent);
        dependsOn = dependsOn == null ? java.util.List.of() : java.util.List.copyOf(dependsOn);
        expectedOutputs = expectedOutputs == null ? java.util.List.of() : java.util.List.copyOf(expectedOutputs);
        note = normalizeNullable(note);
    }

    public PlanTask(String taskId, String title, PlanTaskStatus status, @Nullable String note) {
        this(taskId, title, status, null, null, java.util.List.of(), java.util.List.of(), false, note);
    }

    public PlanTask withStatus(PlanTaskStatus nextStatus, @Nullable String nextNote) {
        return new PlanTask(taskId, title, nextStatus, capabilityName, intent, dependsOn, expectedOutputs, autoCompletable, nextNote);
    }

    public PlanTask bindInProgress(@Nullable String nextNote) {
        return withStatus(PlanTaskStatus.IN_PROGRESS, nextNote);
    }

    public PlanTask complete(@Nullable String nextNote) {
        return withStatus(PlanTaskStatus.COMPLETED, nextNote);
    }

    public PlanTask block(@Nullable String nextNote) {
        return withStatus(PlanTaskStatus.BLOCKED, nextNote);
    }

    public boolean isReady(java.util.Map<String, PlanTask> tasksById) {
        if (status != PlanTaskStatus.PENDING) {
            return false;
        }
        return dependsOn.stream()
                .map(tasksById::get)
                .filter(java.util.Objects::nonNull)
                .allMatch(task -> task.status() == PlanTaskStatus.COMPLETED);
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
