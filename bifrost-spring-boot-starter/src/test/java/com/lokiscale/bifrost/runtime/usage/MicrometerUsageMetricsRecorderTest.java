package com.lokiscale.bifrost.runtime.usage;

import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.linter.LinterOutcomeStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerUsageMetricsRecorderTest {

    @Test
    void emitsMicrometerMetersForModelToolLinterAndGuardrailEvents() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MicrometerUsageMetricsRecorder recorder = new MicrometerUsageMetricsRecorder(meterRegistry);

        recorder.recordSkillInvocation("root.skill");
        recorder.recordModelUsage("root.skill", new ModelUsageRecord(3, 5, 8, UsagePrecision.HEURISTIC, null));
        recorder.recordToolInvocation("root.skill", "tool.one", "success");
        recorder.recordToolAccuracy("root.skill", "regex", "inaccurate");
        recorder.recordLinterOutcome(new LinterOutcome("root.skill", "regex", 2, 1, 2, LinterOutcomeStatus.PASSED, "ok"));
        recorder.recordGuardrailTrip("root.skill", GuardrailType.MAX_MODEL_CALLS);

        assertThat(meterRegistry.get("bifrost.skill.calls").tag("skill", "root.skill").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("bifrost.model.calls").tag("skill", "root.skill").tag("precision", "HEURISTIC").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("bifrost.model.usage.units").tag("skill", "root.skill").tag("precision", "HEURISTIC").counter().count()).isEqualTo(8.0d);
        assertThat(meterRegistry.get("bifrost.tool.calls").tag("skill", "root.skill").tag("tool", "tool.one").tag("outcome", "success").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("bifrost.tool.accuracy.samples").tag("skill", "root.skill").tag("linter", "regex").tag("outcome", "inaccurate").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("bifrost.linter.outcomes").tag("skill", "root.skill").tag("status", "PASSED").tag("linter", "regex").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("bifrost.guardrail.trips").tag("skill", "root.skill").tag("guardrail", "MAX_MODEL_CALLS").tag("outcome", "quota_exceeded").counter().count()).isEqualTo(1.0d);
    }
}
