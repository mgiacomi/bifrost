package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.runtime.input.SkillInputValidationResult;
import com.lokiscale.bifrost.runtime.input.SkillInputValidator;
import com.lokiscale.bifrost.runtime.state.EvidenceSnapshot;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.state.PlanSnapshot;
import com.lokiscale.bifrost.skillapi.SkillInputValidationException;
import com.lokiscale.bifrost.security.AccessGuard;
import com.lokiscale.bifrost.vfs.RefResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Objects;

public class CapabilityExecutionRouter
{
    private final RefResolver refResolver;
    private final ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider;
    private final ExecutionStateService executionStateService;
    private final AccessGuard accessGuard;
    private final SkillInputValidator inputValidator;

    public CapabilityExecutionRouter(RefResolver refResolver,
            ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider,
            ExecutionStateService executionStateService,
            AccessGuard accessGuard)
    {
        this(refResolver, executionCoordinatorProvider, executionStateService, accessGuard, new SkillInputValidator());
    }

    public CapabilityExecutionRouter(RefResolver refResolver,
            ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider,
            ExecutionStateService executionStateService,
            AccessGuard accessGuard,
            SkillInputValidator inputValidator)
    {
        this.refResolver = Objects.requireNonNull(refResolver, "refResolver must not be null");
        this.executionCoordinatorProvider = Objects.requireNonNull(
                executionCoordinatorProvider,
                "executionCoordinatorProvider must not be null");

        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.accessGuard = Objects.requireNonNull(accessGuard, "accessGuard must not be null");
        this.inputValidator = Objects.requireNonNull(inputValidator, "inputValidator must not be null");
    }

    public Object execute(CapabilityMetadata capability,
            Map<String, Object> arguments,
            BifrostSession session,
            @Nullable Authentication authentication)
    {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(session, "session must not be null");

        accessGuard.checkAccess(capability, session, authentication);
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        SkillInputValidationResult validation = inputValidator.validate(safeArguments, capability.inputContract());

        if (!validation.valid())
        {
            throw new SkillInputValidationException("Invalid input for capability '" + capability.name() + "'", validation.issues());
        }

        Map<String, Object> normalizedInput = validation.normalizedInput();

        if (capability.kind() == CapabilityKind.YAML_SKILL && capability.mappedTargetId() == null)
        {
            PlanSnapshot parentPlan = executionStateService.snapshotPlan(session);
            EvidenceSnapshot parentEvidence = executionStateService.snapshotEvidence(session);
            try
            {
                return executionCoordinatorProvider.getObject()
                        .execute(capability.name(), objectiveFor(capability, normalizedInput), normalizedInput, session, authentication);
            }
            finally
            {
                executionStateService.restorePlan(session, parentPlan);
                executionStateService.restoreEvidence(session, parentEvidence);
            }
        }

        return capability.invoker().invoke(refResolver.resolveArguments(normalizedInput, session));
    }

    private String objectiveFor(CapabilityMetadata capability, Map<String, Object> arguments)
    {
        return "Execute YAML skill '%s' using the provided mission input object.".formatted(capability.name());
    }
}
