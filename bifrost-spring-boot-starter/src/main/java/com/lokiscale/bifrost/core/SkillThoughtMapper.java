package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

final class SkillThoughtMapper {

    SkillThoughtTrace toTrace(String route, List<JournalEntry> entries) {
        String normalizedRoute = requireNonBlank(route, "route");
        List<SkillThought> thoughts = (entries == null ? List.<JournalEntry>of() : entries).stream()
                .filter(Objects::nonNull)
                .filter(entry -> normalizedRoute.equals(entry.route()))
                .map(this::toThought)
                .filter(Objects::nonNull)
                .toList();
        return new SkillThoughtTrace(normalizedRoute, thoughts);
    }

    private SkillThought toThought(JournalEntry entry) {
        String content = switch (entry.type()) {
            case THOUGHT -> mapThought(entry.payload());
            case TOOL_CALL -> mapToolCall("Tool call", entry.payload());
            case UNPLANNED_TOOL_EXECUTION -> mapToolCall("Unplanned tool call", entry.payload());
            case TOOL_RESULT -> mapToolResult(entry.payload());
            case ERROR -> mapError(entry.payload());
            case LINTER -> mapLinter(entry.payload());
            default -> null;
        };
        if (content == null) {
            return null;
        }
        return new SkillThought(entry.timestamp(), entry.level(), content);
    }

    private String mapThought(JsonNode payload) {
        if (payload != null && payload.isTextual()) {
            return payload.textValue();
        }
        return "Thought recorded.";
    }

    private String mapToolCall(String prefix, JsonNode payload) {
        String capabilityName = firstNonBlank(
                scalarText(payload, "capabilityName"),
                scalarText(payload, "route"),
                scalarText(payload, "tool"));
        String linkedTaskId = scalarText(payload, "linkedTaskId");
        return joinParts(
                capabilityName == null ? prefix : prefix + ": " + capabilityName,
                linkedTaskId == null ? null : "task " + linkedTaskId);
    }

    private String mapToolResult(JsonNode payload) {
        String capabilityName = firstNonBlank(
                scalarText(payload, "capabilityName"),
                scalarText(payload, "route"),
                scalarText(payload, "tool"));
        String linkedTaskId = scalarText(payload, "linkedTaskId");
        return joinParts(
                capabilityName == null ? "Tool result recorded." : "Tool result: " + capabilityName + " completed",
                linkedTaskId == null ? null : "task " + linkedTaskId);
    }

    private String mapError(JsonNode payload) {
        String tool = firstNonBlank(
                scalarText(payload, "tool"),
                scalarText(payload, "capabilityName"),
                scalarText(payload, "route"));
        String message = scalarText(payload, "message");
        String exceptionType = scalarText(payload, "exceptionType");
        String base = tool == null ? "Error recorded" : "Error in " + tool;
        String withMessage = message == null ? base : base + ": " + message;
        return exceptionType == null ? withMessage : withMessage + " (" + exceptionType + ")";
    }

    private String mapLinter(JsonNode payload) {
        String status = scalarText(payload, "status");
        String skillName = scalarText(payload, "skillName");
        String attempt = scalarText(payload, "attempt");
        String maxRetries = scalarText(payload, "maxRetries");
        String base = status == null ? "Linter recorded" : "Linter " + status;
        String withSkill = skillName == null ? base : base + " for " + skillName;
        String summary = attempt == null || maxRetries == null
                ? withSkill
                : withSkill + " (attempt " + attempt + ", max retries " + maxRetries + ")";
        String linterType = scalarText(payload, "linterType");
        return linterType == null ? summary : summary + " via " + linterType;
    }

    private String scalarText(JsonNode payload, String fieldName) {
        if (payload == null || !payload.isObject()) {
            return null;
        }
        JsonNode field = payload.get(fieldName);
        if (field == null || field.isNull() || field.isContainerNode()) {
            return null;
        }
        return normalizeNullable(field.asText());
    }

    private String joinParts(String first, String second) {
        if (second == null) {
            return first;
        }
        return first + " (" + second + ")";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalizeNullable(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
