package com.lokiscale.bifrost.internal.core;

public interface BifrostExceptionTransformer
{
    String transform(Throwable throwable);
}
