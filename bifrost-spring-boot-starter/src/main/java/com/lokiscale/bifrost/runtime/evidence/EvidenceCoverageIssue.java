package com.lokiscale.bifrost.runtime.evidence;

import java.util.List;

public record EvidenceCoverageIssue(
        String claimName,
        List<String> missingEvidence,
        List<String> supportingTools,
        String message) {
}
