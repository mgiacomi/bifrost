package com.lokiscale.bifrost.skillapi;

import com.lokiscale.bifrost.core.ExecutionJournal;

public record SkillExecutionView(
        String sessionId,
        ExecutionJournal executionJournal) {
}
