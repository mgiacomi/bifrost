package com.lokiscale.loomspan.internal.core;

import java.util.Objects;
import java.util.function.Supplier;

public final class SessionContextRunner
{
    private SessionContextRunner()
    {
    }

    public static <T> T callWithSession(LoomspanSession session, Supplier<T> action)
    {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(action, "action must not be null");
        return LoomspanSessionHolder.callWithSession(session, action);
    }
}
