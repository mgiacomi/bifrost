package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.state.PlanSnapshot;
import com.lokiscale.bifrost.security.AccessGuard;
import com.lokiscale.bifrost.vfs.RefResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Objects;

public class CapabilityExecutionRouter {

    private final RefResolver refResolver;
    private final ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider;
    private final ObjectMapper objectMapper;
    private final ExecutionStateService executionStateService;
    private final AccessGuard accessGuard;

    public CapabilityExecutionRouter(RefResolver refResolver,
                                     ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider,
                                     ExecutionStateService executionStateService,
                                     AccessGuard accessGuard) {
        this(refResolver, executionCoordinatorProvider, new ObjectMapper(), executionStateService, accessGuard);
    }

    CapabilityExecutionRouter(RefResolver refResolver,
                              ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider,
                              ObjectMapper objectMapper,
                              ExecutionStateService executionStateService,
                              AccessGuard accessGuard) {
        this.refResolver = Objects.requireNonNull(refResolver, "refResolver must not be null");
        this.executionCoordinatorProvider = Objects.requireNonNull(
                executionCoordinatorProvider,
                "executionCoordinatorProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.executionStateService = Objects.requireNonNull(executionStateService, "executionStateService must not be null");
        this.accessGuard = Objects.requireNonNull(accessGuard, "accessGuard must not be null");
    }

    public Object execute(CapabilityMetadata capability,
                          Map<String, Object> arguments,
                          BifrostSession session,
                          @Nullable Authentication authentication) {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(session, "session must not be null");

        accessGuard.checkAccess(capability, session, authentication);
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;

        if (capability.kind() == CapabilityKind.YAML_SKILL && capability.mappedTargetId() == null) {
            PlanSnapshot parentPlan = executionStateService.snapshotPlan(session);
            try {
                return executionCoordinatorProvider.getObject()
                        .execute(capability.name(), objectiveFor(capability, safeArguments), session, authentication);
            }
            finally {
                executionStateService.restorePlan(session, parentPlan);
            }
        }

        return capability.invoker().invoke(refResolver.resolveArguments(safeArguments, session));
    }

    private String objectiveFor(CapabilityMetadata capability, Map<String, Object> arguments) {
        try {
            return """
                    Execute YAML skill '%s' using these tool arguments:
                    %s
                    """.formatted(capability.name(), objectMapper.writeValueAsString(arguments));
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize tool arguments for YAML skill '" + capability.name() + "'", ex);
        }
    }
}
