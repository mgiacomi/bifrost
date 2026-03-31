package com.lokiscale.bifrost.runtime.evidence;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record EvidenceCoverageResult(
        Set<String> evaluatedClaims,
        Set<String> requiredEvidence,
        Set<String> availableEvidence,
        List<EvidenceCoverageIssue> issues) {

    public boolean complete() {
        return issues == null || issues.isEmpty();
    }

    public String retryFeedback() {
        if (complete()) {
            return "";
        }
        return issues.stream()
                .map(EvidenceCoverageIssue::message)
                .collect(Collectors.joining("\n"));
    }
}
