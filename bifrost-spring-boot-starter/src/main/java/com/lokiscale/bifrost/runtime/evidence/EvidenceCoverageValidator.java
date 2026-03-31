package com.lokiscale.bifrost.runtime.evidence;

import com.lokiscale.bifrost.core.ExecutionPlan;
import com.lokiscale.bifrost.core.PlanTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class EvidenceCoverageValidator {

    public EvidenceCoverageResult validatePlanCoverage(ExecutionPlan plan, EvidenceContract contract) {
        if (plan == null || contract == null || contract.isEmpty()) {
            return new EvidenceCoverageResult(Set.of(), Set.of(), Set.of(), List.of());
        }

        Set<String> evaluatedClaims = contract.claims();
        Set<String> requiredEvidence = contract.requiredEvidenceForClaims(evaluatedClaims);
        Set<String> availableEvidence = new LinkedHashSet<>();
        for (PlanTask task : plan.tasks()) {
            if (task.capabilityName() != null && !task.capabilityName().isBlank()) {
                availableEvidence.addAll(contract.evidenceProducedByTool(task.capabilityName()));
            }
        }
        return coverageForClaims(evaluatedClaims, requiredEvidence, availableEvidence, contract, true);
    }

    public EvidenceCoverageResult validateEvidenceForClaims(Set<String> presentClaims,
                                                            Set<String> availableEvidence,
                                                            EvidenceContract contract) {
        if (contract == null || contract.isEmpty() || presentClaims == null || presentClaims.isEmpty()) {
            return new EvidenceCoverageResult(
                    presentClaims == null ? Set.of() : Set.copyOf(presentClaims),
                    Set.of(),
                    availableEvidence == null ? Set.of() : Set.copyOf(availableEvidence),
                    List.of());
        }
        Set<String> requiredEvidence = contract.requiredEvidenceForClaims(presentClaims);
        return coverageForClaims(
                Set.copyOf(presentClaims),
                requiredEvidence,
                availableEvidence == null ? Set.of() : Set.copyOf(availableEvidence),
                contract,
                false);
    }

    private EvidenceCoverageResult coverageForClaims(Set<String> evaluatedClaims,
                                                     Set<String> requiredEvidence,
                                                     Set<String> availableEvidence,
                                                     EvidenceContract contract,
                                                     boolean includeSupportingTools) {
        List<EvidenceCoverageIssue> issues = new ArrayList<>();
        for (String claimName : evaluatedClaims) {
            Set<String> missingEvidence = new LinkedHashSet<>(contract.evidenceForClaim(claimName));
            missingEvidence.removeAll(availableEvidence);
            if (missingEvidence.isEmpty()) {
                continue;
            }
            List<String> tools = includeSupportingTools
                    ? contract.evidenceByTool().entrySet().stream()
                    .filter(entry -> entry.getValue().stream().anyMatch(missingEvidence::contains))
                    .map(java.util.Map.Entry::getKey)
                    .sorted()
                    .toList()
                    : List.of();
            String message = includeSupportingTools
                    ? "Validation Error: The plan is missing required evidence %s for the '%s' claim. To fix this, you MUST include a task that uses %s."
                    .formatted(missingEvidence, claimName, tools.isEmpty() ? "a tool that can produce it" : "the " + tools + " tool(s)")
                    : "Validation Error: Execution failed to gather %s required for the '%s' claim (only gathered %s)."
                    .formatted(missingEvidence, claimName, availableEvidence);
            issues.add(new EvidenceCoverageIssue(
                    claimName,
                    missingEvidence.stream().sorted().toList(),
                    tools,
                    message));
        }
        return new EvidenceCoverageResult(
                evaluatedClaims,
                requiredEvidence,
                availableEvidence,
                List.copyOf(issues));
    }
}
