package com.lokiscale.bifrost.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public record ExecutionPlan(
        String planId,
        String capabilityName,
        Instant createdAt,
        PlanStatus status,
        @org.springframework.lang.Nullable String activeTaskId,
        List<PlanTask> tasks) {

    public ExecutionPlan {
        planId = requireNonBlank(planId, "planId");
        capabilityName = requireNonBlank(capabilityName, "capabilityName");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        status = status == null ? PlanStatus.VALID : status;
        activeTaskId = normalizeNullable(activeTaskId);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    public ExecutionPlan(String planId,
                         String capabilityName,
                         Instant createdAt,
                         List<PlanTask> tasks) {
        this(planId, capabilityName, createdAt, PlanStatus.VALID, null, tasks);
    }

    public ExecutionPlan updateTask(String taskId, Function<PlanTask, PlanTask> updater) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(updater, "updater must not be null");
        return new ExecutionPlan(
                planId,
                capabilityName,
                createdAt,
                status,
                activeTaskId,
                tasks.stream()
                        .map(task -> task.taskId().equals(taskId) ? Objects.requireNonNull(updater.apply(task)) : task)
                        .toList());
    }

    public java.util.Optional<PlanTask> findTask(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return tasks.stream().filter(task -> task.taskId().equals(taskId)).findFirst();
    }

    public java.util.Optional<PlanTask> activeTask() {
        return activeTaskId == null ? java.util.Optional.empty() : findTask(activeTaskId);
    }

    public List<PlanTask> readyTasks() {
        java.util.Map<String, PlanTask> tasksById = tasksById();
        return tasks.stream().filter(task -> task.isReady(tasksById)).toList();
    }

    public ExecutionPlan withActiveTask(@org.springframework.lang.Nullable String taskId) {
        return new ExecutionPlan(planId, capabilityName, createdAt, status, taskId, tasks);
    }

    public ExecutionPlan clearActiveTask() {
        return withActiveTask(null);
    }

    public ExecutionPlan withStatus(PlanStatus nextStatus) {
        return new ExecutionPlan(planId, capabilityName, createdAt, nextStatus, activeTaskId, tasks);
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private java.util.Map<String, PlanTask> tasksById() {
        return tasks.stream().collect(java.util.stream.Collectors.toMap(
                PlanTask::taskId,
                Function.identity(),
                (left, right) -> left,
                java.util.LinkedHashMap::new));
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
