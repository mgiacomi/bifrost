package com.lokiscale.bifrost.core;

public class CapabilityCollisionException extends RuntimeException {

    public CapabilityCollisionException(String message) {
        super(message);
    }

    public CapabilityCollisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
