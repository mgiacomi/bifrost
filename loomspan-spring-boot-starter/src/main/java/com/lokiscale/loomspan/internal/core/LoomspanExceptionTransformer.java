package com.lokiscale.loomspan.internal.core;

public interface LoomspanExceptionTransformer
{
    String transform(Throwable throwable);
}
