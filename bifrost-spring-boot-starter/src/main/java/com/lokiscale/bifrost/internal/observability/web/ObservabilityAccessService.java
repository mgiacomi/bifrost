package com.lokiscale.bifrost.internal.observability.web;

import org.springframework.security.core.Authentication;

public final class ObservabilityAccessService
{
    public enum Operation { INSTANCE_READ, SKILL_READ, ACTIVE_READ, TRACE_READ }
    public static final String AUTHORITY = "BIFROST_OPERATOR";

    public void require(Operation operation, Authentication authentication)
    {
        if (operation == null || !(authentication instanceof ObservabilityOperatorAuthentication)
                || !authentication.isAuthenticated())
        {
            throw new ObservabilityException(401,
                    ObservabilityProblem.Code.BIFROST_API_KEY_REJECTED, "Bifrost API key was rejected");
        }
    }
}
