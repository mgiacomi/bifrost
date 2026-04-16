package com.lokiscale.bifrost.runtime.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public record PlanQualityValidationResult(List<PlanQualityIssue> warnings, List<PlanQualityIssue> errors)
{
    public PlanQualityValidationResult
    {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean hasWarnings()
    {
        return !warnings.isEmpty();
    }

    public boolean hasErrors()
    {
        return !errors.isEmpty();
    }

    public List<PlanQualityIssue> allIssues()
    {
        ArrayList<PlanQualityIssue> issues = new ArrayList<>(errors.size() + warnings.size());
        issues.addAll(errors);
        issues.addAll(warnings);
        return List.copyOf(issues);
    }

    public String retryFeedback()
    {
        return errors.stream()
                .map(PlanQualityIssue::message)
                .distinct()
                .map(message -> "- " + message)
                .collect(Collectors.joining("\n"));
    }

    public List<String> issueCodes()
    {
        return allIssues().stream()
                .map(PlanQualityIssue::code)
                .distinct()
                .toList();
    }
}
