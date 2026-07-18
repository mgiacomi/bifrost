package com.lokiscale.bifrost.internal.runtime.state;

import com.lokiscale.bifrost.internal.core.BifrostSession;
import com.lokiscale.bifrost.internal.core.ExecutionFrame;
import com.lokiscale.bifrost.internal.core.TraceRecord;
import com.lokiscale.bifrost.internal.core.TraceRecordType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SuccessfulSkillTraceTest
{
    @Test
    void repeatedSuccessIsSetIdempotentButRetainsDistinctEvidenceEvents()
    {
        DefaultExecutionStateService state = new DefaultExecutionStateService(Clock.fixed(
                Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC));
        BifrostSession session = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("trace", 3);
        ExecutionFrame frame = state.openMissionFrame(session, "handleIncident", Map.of());

        state.recordSuccessfulSkill(session, "investigateNetwork", "task-1", false);
        state.recordSuccessfulSkill(session, "investigateNetwork", "task-2", false);
        state.closeMissionFrame(session, frame);

        assertThat(session.getSuccessfulDirectSkills()).containsExactly("investigateNetwork");
        List<TraceRecord> records = new ArrayList<>();
        session.readTraceRecords(records::add);
        assertThat(records).filteredOn(record -> record.recordType() == TraceRecordType.EVIDENCE_RECORDED)
                .hasSize(2)
                .allSatisfy(record ->
                {
                    assertThat(record.data().path("successfulSkill").asText()).isEqualTo("investigateNetwork");
                    assertThat(record.data().path("successfulDirectSkills")).hasSize(1);
                    assertThat(record.data().has("evidenceTypes")).isFalse();
                    assertThat(record.data().has("ledger")).isFalse();
                });
    }
}
