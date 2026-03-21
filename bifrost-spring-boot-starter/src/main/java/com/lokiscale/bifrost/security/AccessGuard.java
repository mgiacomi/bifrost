package com.lokiscale.bifrost.security;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

public interface AccessGuard {

    @Nullable
    Authentication resolveAuthentication(@Nullable Authentication invocationAuthentication, BifrostSession session);

    boolean canAccess(CapabilityMetadata capability,
                      BifrostSession session,
                      @Nullable Authentication invocationAuthentication);

    void checkAccess(CapabilityMetadata capability,
                     BifrostSession session,
                     @Nullable Authentication invocationAuthentication);
}
