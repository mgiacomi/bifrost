package com.lokiscale.bifrost.core;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public class BifrostSessionRunner {

    private final int maxDepth;

    public BifrostSessionRunner(int maxDepth) {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be greater than zero");
        }
        this.maxDepth = maxDepth;
    }

    public void runWithNewSession(Consumer<BifrostSession> action) {
        Objects.requireNonNull(action, "action must not be null");
        BifrostSession session = new BifrostSession(maxDepth);
        BifrostSessionHolder.runWithSession(session, () -> action.accept(session));
    }

    public <T> T callWithNewSession(Function<BifrostSession, T> action) {
        Objects.requireNonNull(action, "action must not be null");
        BifrostSession session = new BifrostSession(maxDepth);
        return BifrostSessionHolder.callWithSession(session, () -> action.apply(session));
    }
}
