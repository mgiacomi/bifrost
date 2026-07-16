package com.lokiscale.bifrost.internal.core;

class CapabilityCollisionException extends RuntimeException
{
    public CapabilityCollisionException(String message)
    {
        super(message);
    }

    public CapabilityCollisionException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
