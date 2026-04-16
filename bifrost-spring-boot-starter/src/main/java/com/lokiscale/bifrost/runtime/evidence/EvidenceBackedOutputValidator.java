package com.lokiscale.bifrost.runtime.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Set;

public final class EvidenceBackedOutputValidator
{
    private final ObjectMapper objectMapper;
    private final EvidenceCoverageValidator coverageValidator;

    public EvidenceBackedOutputValidator()
    {
        this(JsonMapper.builder().findAndAddModules().build(), new EvidenceCoverageValidator());
    }

    EvidenceBackedOutputValidator(ObjectMapper objectMapper,
            EvidenceCoverageValidator coverageValidator)
    {
        this.objectMapper = objectMapper;
        this.coverageValidator = coverageValidator;
    }

    public EvidenceCoverageResult validate(String rawOutput,
            EvidenceContract contract,
            Set<String> gatheredEvidence)
    {
        if (contract == null || contract.isEmpty())
        {
            return new EvidenceCoverageResult(Set.of(), Set.of(), gatheredEvidence == null ? Set.of() : Set.copyOf(gatheredEvidence), java.util.List.of());
        }
        try
        {
            return validate(objectMapper.readTree(rawOutput == null ? "{}" : rawOutput), contract, gatheredEvidence);
        }
        catch (JsonProcessingException ex)
        {
            throw new IllegalStateException("Evidence validation expected schema-valid JSON but could not parse it.", ex);
        }
    }

    public EvidenceCoverageResult validate(JsonNode candidate,
            EvidenceContract contract,
            Set<String> gatheredEvidence)
    {
        if (contract == null || contract.isEmpty())
        {
            return new EvidenceCoverageResult(Set.of(), Set.of(), gatheredEvidence == null ? Set.of() : Set.copyOf(gatheredEvidence), java.util.List.of());
        }

        Set<String> presentClaims = contract.presentClaims(candidate);
        return coverageValidator.validateEvidenceForClaims(presentClaims, gatheredEvidence, contract);
    }
}
