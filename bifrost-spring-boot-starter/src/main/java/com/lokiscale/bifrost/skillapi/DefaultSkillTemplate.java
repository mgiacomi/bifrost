package com.lokiscale.bifrost.skillapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.BifrostSessionRunner;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.runtime.input.SkillInputContract;
import com.lokiscale.bifrost.runtime.input.SkillInputValidationResult;
import com.lokiscale.bifrost.runtime.input.SkillInputValidator;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class DefaultSkillTemplate implements SkillTemplate
{
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>()
    {
    };

    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityExecutionRouter executionRouter;
    private final BifrostSessionRunner sessionRunner;
    private final ObjectMapper objectMapper;
    private final SkillInputValidator inputValidator;

    public DefaultSkillTemplate(CapabilityRegistry capabilityRegistry,
            CapabilityExecutionRouter executionRouter,
            BifrostSessionRunner sessionRunner,
            ObjectMapper objectMapper,
            SkillInputValidator inputValidator)
    {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
        this.executionRouter = Objects.requireNonNull(executionRouter, "executionRouter must not be null");
        this.sessionRunner = Objects.requireNonNull(sessionRunner, "sessionRunner must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.inputValidator = Objects.requireNonNull(inputValidator, "inputValidator must not be null");
    }

    @Override
    public String invoke(String skillName, Object input)
    {
        return invoke(skillName, input, null);
    }

    @Override
    public String invoke(String skillName, Map<String, Object> input)
    {
        return invoke(skillName, input, null);
    }

    @Override
    public String invoke(String skillName, Object input, Consumer<SkillExecutionView> observer)
    {
        if (input == null)
        {
            throw new SkillInputValidationException("Skill input must not be null.", List.of());
        }
        return invoke(skillName, objectMapper.convertValue(input, MAP_TYPE), observer);
    }

    @Override
    public String invoke(String skillName, Map<String, Object> input, Consumer<SkillExecutionView> observer)
    {
        CapabilityMetadata capability = requireYamlSkill(skillName);
        SkillInputContract contract = capability.inputContract();
        Map<String, Object> safeInput = normalizeNullInput(input, contract);
        SkillInputValidationResult validation = inputValidator.validate(safeInput, contract);

        if (!validation.valid())
        {
            throw new SkillInputValidationException(buildValidationMessage(skillName, validation), validation.issues());
        }

        ExecutionResult execution = sessionRunner.callWithNewSession(session -> executeValidated(capability, validation, session));

        if (observer != null)
        {
            observer.accept(new SkillExecutionView(execution.session().getSessionId(), execution.session().getExecutionJournal()));
        }

        return execution.result();
    }

    private ExecutionResult executeValidated(CapabilityMetadata capability,
            SkillInputValidationResult validation,
            BifrostSession session)
    {
        Object result = executionRouter.execute(capability, validation.normalizedInput(), session, null);
        return new ExecutionResult(String.valueOf(result), session);
    }

    private CapabilityMetadata requireYamlSkill(String skillName)
    {
        CapabilityMetadata capability = capabilityRegistry.getCapability(skillName);
        if (capability == null)
        {
            throw new SkillException("Unknown YAML skill '" + skillName + "'");
        }
        if (capability.kind() != com.lokiscale.bifrost.core.CapabilityKind.YAML_SKILL)
        {
            throw new SkillException("SkillTemplate only supports YAML skills. '" + skillName + "' is a " + capability.kind());
        }

        return capability;
    }

    private Map<String, Object> normalizeNullInput(Map<String, Object> input, SkillInputContract contract)
    {
        if (input != null)
        {
            return input;
        }
        if (contract.isGeneric() || contract.allowsEmptyInput())
        {
            return Map.of();
        }

        throw new SkillInputValidationException("Skill input must not be null for contract-backed skills.", List.of());
    }

    private String buildValidationMessage(String skillName, SkillInputValidationResult validation)
    {
        String detail = validation.issues().stream()
                .map(issue -> (issue.path() == null || issue.path().isBlank() ? "<root>" : issue.path()) + ": " + issue.message())
                .reduce((left, right) -> left + "; " + right)
                .orElse("Invalid skill input.");

        return "Invalid input for skill '" + skillName + "': " + detail;
    }

    private record ExecutionResult(String result, BifrostSession session)
    {
    }
}
