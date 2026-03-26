package com.lokiscale.bifrost.core;

@FunctionalInterface
public interface ModelTraceCallback<T> {

    ModelTraceResult<T> execute(java.util.function.Consumer<Object> markRequestSent);
}
