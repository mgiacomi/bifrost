package com.lokiscale.bifrost.core;

import java.util.Optional;
import java.util.function.Supplier;

final class BifrostSessionHolder
{
    private static final ThreadLocal<BifrostSession> CURRENT = new ThreadLocal<>();

    private BifrostSessionHolder()
    {
    }

    static void runWithSession(BifrostSession session, Runnable action)
    {
        BifrostSession previous = CURRENT.get();
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

    static <T> T callWithSession(BifrostSession session, Supplier<T> action)
    {
        BifrostSession previous = CURRENT.get();
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

    static Optional<BifrostSession> currentSession()
    {
        return Optional.ofNullable(CURRENT.get());
    }

    static BifrostSession requireCurrentSession()
    {
        return currentSession().orElseThrow(() -> new IllegalStateException("No active Bifrost session is bound to the current execution."));
    }

    private static void restore(BifrostSession previous)
    {
        if (previous == null)
        {
            CURRENT.remove();
            return;
        }
        CURRENT.set(previous);
    }
}
