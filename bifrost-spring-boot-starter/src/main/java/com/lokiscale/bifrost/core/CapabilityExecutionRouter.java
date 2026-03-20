package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.vfs.RefResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class CapabilityExecutionRouter {

    private final RefResolver refResolver;
    private final ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider;
    private final ObjectMapper objectMapper;

    public CapabilityExecutionRouter(RefResolver refResolver,
                                     ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider) {
        this(refResolver, executionCoordinatorProvider, new ObjectMapper());
    }

    CapabilityExecutionRouter(RefResolver refResolver,
                              ObjectProvider<ExecutionCoordinator> executionCoordinatorProvider,
                              ObjectMapper objectMapper) {
        this.refResolver = Objects.requireNonNull(refResolver, "refResolver must not be null");
        this.executionCoordinatorProvider = Objects.requireNonNull(
                executionCoordinatorProvider,
                "executionCoordinatorProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public Object execute(CapabilityMetadata capability,
                          Map<String, Object> arguments,
                          BifrostSession session,
                          @Nullable Authentication authentication) {
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(session, "session must not be null");

        ensureAuthorized(capability, authentication);
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;

        if (capability.kind() == CapabilityKind.YAML_SKILL && capability.mappedTargetId() == null) {
            java.util.Optional<ExecutionPlan> parentPlan = session.getExecutionPlan();
            try {
                return executionCoordinatorProvider.getObject()
                        .execute(capability.name(), objectiveFor(capability, safeArguments), session, authentication);
            }
            finally {
                parentPlan.ifPresentOrElse(session::replaceExecutionPlan, session::clearExecutionPlan);
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

    private void ensureAuthorized(CapabilityMetadata capability, @Nullable Authentication authentication) {
        if (capability.rbacRoles().isEmpty()) {
            return;
        }
        Set<String> authorities = authentication == null
                ? Set.of()
                : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        boolean authorized = capability.rbacRoles().stream().anyMatch(authorities::contains);
        if (!authorized) {
            throw new AccessDeniedException("Access denied for capability '" + capability.name() + "'");
        }
    }
}
