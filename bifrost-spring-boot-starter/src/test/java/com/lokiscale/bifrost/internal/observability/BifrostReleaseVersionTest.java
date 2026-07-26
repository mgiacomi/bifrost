package com.lokiscale.bifrost.internal.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BifrostReleaseVersionTest
{
    @Test
    void loadsCompleteFilteredMavenReleaseIncludingQualifier()
    {
        assertThat(BifrostReleaseVersion.load()).isEqualTo("0.1.0-SNAPSHOT");
    }
}
