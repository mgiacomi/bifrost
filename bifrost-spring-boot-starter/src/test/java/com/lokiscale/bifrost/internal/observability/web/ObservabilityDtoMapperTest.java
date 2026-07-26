package com.lokiscale.bifrost.internal.observability.web;

import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import com.lokiscale.bifrost.internal.core.TraceFrameType;
import com.lokiscale.bifrost.internal.runtime.observation.ActiveExecutionSnapshot;
import com.lokiscale.bifrost.internal.runtime.observation.ExecutionActivity;
import com.lokiscale.bifrost.internal.runtime.observation.ExecutionActivityKind;
import com.lokiscale.bifrost.internal.runtime.observation.catalog.RegisteredSkillFile;
import com.lokiscale.bifrost.internal.runtime.usage.SessionUsageSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityDtoMapperTest
{
    private final ObservabilityDtoMapper mapper = new ObservabilityDtoMapper();

    @Test
    @DisplayName("WF-SE-R1 WF-SE-R2 WF-SE-R6 WF-SE-R7 WF-SP-R2: active DTO is a bounded factual snapshot")
    void activeElapsedIsDerivedAtObservationTimeAndNeverCreatesHealthClaims()
    {
        Instant started = Instant.parse("2026-07-25T12:00:00Z");
        ActiveExecutionSnapshot snapshot = new ActiveExecutionSnapshot(
                "session", "trace", 7, 9, started, started, "entry", "RUNNING", "working",
                List.of(new ActiveExecutionSnapshot.FramePathEntry("frame", TraceFrameType.SKILL_EXECUTION, "entry")),
                1, false, SessionUsageSnapshot.empty(), null);

        var dto = mapper.active(snapshot, started.minusSeconds(1), new BifrostProperties.Session.Quotas());

        assertThat(dto.elapsedMillis()).isZero();
        assertThat(dto.activePath()).hasSize(1);
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.usage().getClass().getName())
                .isEqualTo("com.lokiscale.bifrost.internal.observability.web.dto.ObservabilityDtos$Usage");
        assertThat(dto.usage().usageUnits()).isZero();
        assertThat(dto.toString()).doesNotContain("stuck", "health");
    }

    @Test
    @DisplayName("WF-SP-R7 WF-SP-R8 WF-SP-R9: skill link and unchanged YAML stay path-safe")
    void skillProjectionUsesApiRootRelativeLinkAndUnchangedYaml()
    {
        String yaml = "# comment\r\nname: Check Dns\r\n";
        RegisteredSkillFile file = new RegisteredSkillFile("Check Dns", "classpath:/skills/check.yaml", yaml);
        assertThat(mapper.skill(file.summary()).href()).isEqualTo("skills/Check%20Dns");
        assertThat(mapper.skill(file).yaml()).isEqualTo(yaml);
    }

    @Test
    @DisplayName("WF-X-R7 WF-SE-R9: activity envelope is flat, instance-scoped, and cursor-exact")
    void mapsActivityToFlatInstanceScopedEnvelope() throws Exception
    {
        ExecutionActivity activity = new ExecutionActivity(
                42, "session", "trace", 7L, Instant.parse("2026-07-26T12:00:00Z"),
                ExecutionActivityKind.TOOL_CALL_STARTED, "frame", "parent",
                TraceFrameType.TOOL_INVOCATION, "route", "ACTIVE", "line one\nline two",
                Map.of("capabilityName", "lookup"), 300);

        var dto = mapper.activity("instance", activity);
        byte[] encoded = new ObservabilityJsonCodec().write(dto);
        String json = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(dto.cursor()).isEqualTo("42");
        assertThat(dto.parentFrameId()).isEqualTo("parent");
        assertThat(dto.executionStatus()).isEqualTo("ACTIVE");
        assertThat(json).contains("\"instanceId\":\"instance\"", "\"cursor\":\"42\"")
                .contains("\\n")
                .doesNotContain("retainedWeight", "consoleCompatibilityVersion", "apiKey");
    }
}
