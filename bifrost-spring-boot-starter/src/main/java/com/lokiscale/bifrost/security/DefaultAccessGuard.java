package com.lokiscale.bifrost.security;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class DefaultAccessGuard implements AccessGuard
{
    @Override
    @Nullable
    public Authentication resolveAuthentication(@Nullable Authentication invocationAuthentication, BifrostSession session)
    {
        Objects.requireNonNull(session, "session must not be null");
        // Explicit invocation auth is authoritative; session auth is a fallback for nested or detached execution.
        return invocationAuthentication != null ? invocationAuthentication : session.getAuthentication().orElse(null);
    }

    @Override
    public boolean canAccess(CapabilityMetadata capability, BifrostSession session, @Nullable Authentication invocationAuthentication)
    {
        Objects.requireNonNull(capability, "capability must not be null");
        Authentication authentication = resolveAuthentication(invocationAuthentication, session);

        if (capability.rbacRoles().isEmpty())
        {
            return true;
        }
        if (authentication == null)
        {
            return false;
        }

        Set<String> authorities = authorities(authentication);

        return capability.rbacRoles().stream().anyMatch(authorities::contains);
    }

    @Override
    public void checkAccess(CapabilityMetadata capability, BifrostSession session, @Nullable Authentication invocationAuthentication)
    {
        if (!canAccess(capability, session, invocationAuthentication))
        {
            throw new AccessDeniedException("Access denied for capability '" + capability.name() + "'");
        }
    }

    private Set<String> authorities(Authentication authentication)
    {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
