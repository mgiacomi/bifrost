package com.lokiscale.bifrost.core;

import java.util.function.Consumer;

@FunctionalInterface
public interface ModelTraceCallback<T>
{
    ModelTraceResult<T> execute(Consumer<Object> markRequestSent);
}
