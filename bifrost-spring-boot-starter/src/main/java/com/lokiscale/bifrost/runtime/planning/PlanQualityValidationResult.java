package com.lokiscale.bifrost.runtime.planning;

import java.util.List;

public record PlanQualityValidationResult(
        List<PlanQualityIssue> warnings,
        List<PlanQualityIssue> errors) {

    public PlanQualityValidationResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<PlanQualityIssue> allIssues() {
        java.util.ArrayList<PlanQualityIssue> issues = new java.util.ArrayList<>(errors.size() + warnings.size());
        issues.addAll(errors);
        issues.addAll(warnings);
        return List.copyOf(issues);
    }

    public String retryFeedback() {
        return errors.stream()
                .map(PlanQualityIssue::message)
                .distinct()
                .map(message -> "- " + message)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    public List<String> issueCodes() {
        return allIssues().stream()
                .map(PlanQualityIssue::code)
                .distinct()
                .toList();
    }
}
