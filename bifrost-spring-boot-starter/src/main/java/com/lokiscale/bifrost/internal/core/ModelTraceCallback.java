package com.lokiscale.bifrost.internal.core;

import java.util.function.Consumer;

@FunctionalInterface
public interface ModelTraceCallback<T>
{
    ModelTraceResult<T> execute(Consumer<Object> markRequestSent);
}
