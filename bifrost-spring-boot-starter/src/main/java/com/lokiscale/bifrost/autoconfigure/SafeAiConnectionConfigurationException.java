package com.lokiscale.bifrost.autoconfigure;

/**
 * Marks a connection-construction diagnostic whose message contains only a safe
 * configuration path and non-sensitive explanation.
 */
final class SafeAiConnectionConfigurationException extends IllegalStateException
{
    SafeAiConnectionConfigurationException(String message)
    {
        super(message);
    }
}
