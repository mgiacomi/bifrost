package com.lokiscale.bifrost.runtime.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.lokiscale.bifrost.skill.YamlSkillManifest;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class EvidenceContract {

    private static final EvidenceContract EMPTY = new EvidenceContract(Map.of(), Map.of(), Map.of(), Map.of());

    private final Map<String, Set<String>> evidenceByClaim;
    private final Map<String, Set<String>> evidenceByTool;
    private final Map<String, String> canonicalClaimByNormalized;
    private final Map<String, String> canonicalToolByNormalized;

    private EvidenceContract(Map<String, Set<String>> evidenceByClaim,
                             Map<String, Set<String>> evidenceByTool,
                             Map<String, String> canonicalClaimByNormalized,
                             Map<String, String> canonicalToolByNormalized) {
        this.evidenceByClaim = evidenceByClaim;
        this.evidenceByTool = evidenceByTool;
        this.canonicalClaimByNormalized = canonicalClaimByNormalized;
        this.canonicalToolByNormalized = canonicalToolByNormalized;
    }

    public static EvidenceContract empty() {
        return EMPTY;
    }

    public static EvidenceContract fromManifest(YamlSkillManifest.EvidenceContractManifest manifest,
                                                YamlSkillManifest.OutputSchemaManifest outputSchema) {
        if (manifest == null) {
            return empty();
        }

        Map<String, String> schemaPropertiesByNormalized = new LinkedHashMap<>();
        if (outputSchema != null) {
            outputSchema.getProperties().keySet().forEach(propertyName ->
                    schemaPropertiesByNormalized.put(normalizeKey(propertyName), propertyName));
        }

        Map<String, Set<String>> evidenceByClaim = new LinkedHashMap<>();
        Map<String, String> canonicalClaimByNormalized = new LinkedHashMap<>();
        manifest.getClaims().forEach((claimName, evidenceTypes) -> {
            String canonicalClaim = schemaPropertiesByNormalized.getOrDefault(normalizeKey(claimName), claimName);
            evidenceByClaim.put(canonicalClaim, copyEvidenceSet(evidenceTypes));
            canonicalClaimByNormalized.put(normalizeKey(canonicalClaim), canonicalClaim);
        });

        Map<String, Set<String>> evidenceByTool = new LinkedHashMap<>();
        Map<String, String> canonicalToolByNormalized = new LinkedHashMap<>();
        manifest.getToolEvidence().forEach((toolName, evidenceTypes) -> {
            evidenceByTool.put(toolName, copyEvidenceSet(evidenceTypes));
            canonicalToolByNormalized.put(normalizeKey(toolName), toolName);
        });

        return new EvidenceContract(
                Map.copyOf(evidenceByClaim),
                Map.copyOf(evidenceByTool),
                Map.copyOf(canonicalClaimByNormalized),
                Map.copyOf(canonicalToolByNormalized));
    }

    public boolean isEmpty() {
        return evidenceByClaim.isEmpty() && evidenceByTool.isEmpty();
    }

    public Set<String> claims() {
        return evidenceByClaim.keySet();
    }

    public Set<String> evidenceForClaim(String claimName) {
        String canonical = canonicalClaimByNormalized.get(normalizeKey(claimName));
        if (canonical == null) {
            return Set.of();
        }
        return evidenceByClaim.getOrDefault(canonical, Set.of());
    }

    public Set<String> evidenceProducedByTool(String toolName) {
        String canonical = canonicalToolByNormalized.get(normalizeKey(toolName));
        if (canonical == null) {
            return Set.of();
        }
        return evidenceByTool.getOrDefault(canonical, Set.of());
    }

    public Set<String> requiredEvidenceForClaims(Collection<String> claimNames) {
        if (claimNames == null || claimNames.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> requiredEvidence = new LinkedHashSet<>();
        claimNames.forEach(claimName -> requiredEvidence.addAll(evidenceForClaim(claimName)));
        return Set.copyOf(requiredEvidence);
    }

    public Set<String> presentClaims(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) {
            return Set.of();
        }
        LinkedHashSet<String> presentClaims = new LinkedHashSet<>();
        candidate.fieldNames().forEachRemaining(fieldName -> {
            String canonicalClaim = canonicalClaimByNormalized.get(normalizeKey(fieldName));
            if (canonicalClaim != null) {
                presentClaims.add(canonicalClaim);
            }
        });
        return Set.copyOf(presentClaims);
    }

    public Map<String, Set<String>> evidenceByClaim() {
        return evidenceByClaim;
    }

    public Map<String, Set<String>> evidenceByTool() {
        return evidenceByTool;
    }

    private static Set<String> copyEvidenceSet(Collection<String> evidenceTypes) {
        if (evidenceTypes == null || evidenceTypes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        evidenceTypes.stream()
                .filter(Objects::nonNull)
                .forEach(normalized::add);
        return Set.copyOf(normalized);
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
