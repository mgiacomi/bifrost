package com.lokiscale.bifrost.core;

public class BifrostStackOverflowException extends RuntimeException {

    public BifrostStackOverflowException(String sessionId, int maxDepth, String route) {
        super("Session " + sessionId + " exceeded max depth " + maxDepth + " while opening route " + route + ".");
    }
}
