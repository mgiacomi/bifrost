package com.lokiscale.bifrost.core;

public interface BifrostExceptionTransformer {

    String transform(Throwable throwable);
}
