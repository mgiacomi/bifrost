package com.lokiscale.loomspan.internal.core;

import java.util.Optional;
import java.util.function.Supplier;

final class LoomspanSessionHolder
{
    private static final ThreadLocal<LoomspanSession> CURRENT = new ThreadLocal<>();

    private LoomspanSessionHolder()
    {
    }

    static void runWithSession(LoomspanSession session, Runnable action)
    {
        LoomspanSession previous = CURRENT.get();
        CURRENT.set(session);
        try
        {
            action.run();
        }
        finally
        {
            restore(previous);
        }
    }

    static <T> T callWithSession(LoomspanSession session, Supplier<T> action)
    {
        LoomspanSession previous = CURRENT.get();
        CURRENT.set(session);
        try
        {
            return action.get();
        }
        finally
        {
            restore(previous);
        }
    }

    static Optional<LoomspanSession> currentSession()
    {
        return Optional.ofNullable(CURRENT.get());
    }

    static LoomspanSession requireCurrentSession()
    {
        return currentSession().orElseThrow(() -> new IllegalStateException("No active Loomspan session is bound to the current execution."));
    }

    private static void restore(LoomspanSession previous)
    {
        if (previous == null)
        {
            CURRENT.remove();
            return;
        }
        CURRENT.set(previous);
    }
}
