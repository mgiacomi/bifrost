package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcome;
import com.lokiscale.bifrost.runtime.usage.SessionUsageSnapshot;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

public final class BifrostSession {

    private static final SkillThoughtMapper SKILL_THOUGHT_MAPPER = new SkillThoughtMapper();

    private final String sessionId;
    private final int maxDepth;
    @JsonIgnore
    private final ReentrantLock lock;
    @JsonIgnore
    private final Deque<ExecutionFrame> frames;
    @JsonIgnore
    private final Map<String, Integer> toolActivityCountByFrameId;
    private final ExecutionJournal executionJournal;
    private ExecutionPlan executionPlan;
    private LinterOutcome lastLinterOutcome;
    private OutputSchemaOutcome lastOutputSchemaOutcome;
    private SessionUsageSnapshot sessionUsage;
    @JsonIgnore
    private Authentication authentication;

    public BifrostSession(int maxDepth) {
        this(UUID.randomUUID().toString(), maxDepth);
    }

    public BifrostSession(String sessionId, int maxDepth) {
        this(sessionId, maxDepth, List.of(), new ExecutionJournal(), null, null, null, null, null);
    }

    public BifrostSession(int maxDepth, @Nullable Authentication authentication) {
        this(UUID.randomUUID().toString(), maxDepth, List.of(), new ExecutionJournal(), null, null, null, null, authentication);
    }

    public BifrostSession(String sessionId, int maxDepth, @Nullable Authentication authentication) {
        this(sessionId, maxDepth, List.of(), new ExecutionJournal(), null, null, null, null, authentication);
    }

    @JsonCreator
    public BifrostSession(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("maxDepth") int maxDepth,
            @JsonProperty("frames") List<ExecutionFrame> frames,
            @JsonProperty("executionJournal") ExecutionJournal executionJournal,
            @JsonProperty("executionPlan") ExecutionPlan executionPlan,
            @JsonProperty("lastLinterOutcome") LinterOutcome lastLinterOutcome,
            @JsonProperty("lastOutputSchemaOutcome") OutputSchemaOutcome lastOutputSchemaOutcome,
            @JsonProperty("sessionUsage") SessionUsageSnapshot sessionUsage) {
        this(sessionId, maxDepth, frames, executionJournal, executionPlan, lastLinterOutcome, lastOutputSchemaOutcome, sessionUsage, null);
    }

    public BifrostSession(
            String sessionId,
            int maxDepth,
            List<ExecutionFrame> frames,
            ExecutionJournal executionJournal,
            ExecutionPlan executionPlan,
            @Nullable LinterOutcome lastLinterOutcome,
            @Nullable OutputSchemaOutcome lastOutputSchemaOutcome,
            @Nullable SessionUsageSnapshot sessionUsage,
            @Nullable Authentication authentication) {
        this.sessionId = requireNonBlank(sessionId, "sessionId");
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be greater than zero");
        }
        this.maxDepth = maxDepth;
        this.lock = new ReentrantLock();
        this.frames = new ArrayDeque<>(frames == null ? List.of() : List.copyOf(frames));
        this.toolActivityCountByFrameId = new HashMap<>();
        this.executionJournal = executionJournal == null ? new ExecutionJournal() : executionJournal;
        this.executionPlan = executionPlan;
        this.lastLinterOutcome = lastLinterOutcome;
        this.lastOutputSchemaOutcome = lastOutputSchemaOutcome;
        this.sessionUsage = sessionUsage;
        this.authentication = authentication;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void logThought(Instant timestamp, Object payload) {
        appendJournalEntry(timestamp, JournalLevel.INFO, JournalEntryType.THOUGHT, payload);
    }

    public void logToolExecution(Instant timestamp, Object payload) {
        appendJournalEntry(timestamp, JournalLevel.INFO, JournalEntryType.TOOL_CALL, payload);
    }

    public void logUnplannedToolExecution(Instant timestamp, TaskExecutionEvent event) {
        appendJournalEntry(timestamp, JournalLevel.INFO, JournalEntryType.UNPLANNED_TOOL_EXECUTION, event);
    }

    public void logToolResult(Instant timestamp, Object payload) {
        appendJournalEntry(timestamp, JournalLevel.INFO, JournalEntryType.TOOL_RESULT, payload);
    }

    public void logPlanCreated(Instant timestamp, ExecutionPlan plan) {
        appendJournalEntry(timestamp, JournalLevel.INFO, JournalEntryType.PLAN_CREATED, plan);
    }

    public void logPlanUpdated(Instant timestamp, ExecutionPlan plan) {
        appendJournalEntry(timestamp, JournalLevel.INFO, JournalEntryType.PLAN_UPDATED, plan);
    }

    public void logLinterOutcome(Instant timestamp, LinterOutcome outcome) {
        appendJournalEntry(timestamp, JournalLevel.INFO, JournalEntryType.LINTER, outcome);
    }

    public void logOutputSchemaOutcome(Instant timestamp, OutputSchemaOutcome outcome) {
        appendJournalEntry(timestamp, JournalLevel.INFO, JournalEntryType.OUTPUT_SCHEMA, outcome);
    }

    public void logError(Instant timestamp, Object payload) {
        appendJournalEntry(timestamp, JournalLevel.ERROR, JournalEntryType.ERROR, payload);
    }

    public SkillThoughtTrace getSkillThoughts(String route) {
        String normalizedRoute = requireNonBlank(route, "route");
        lock.lock();
        try {
            return SKILL_THOUGHT_MAPPER.toTrace(normalizedRoute, executionJournal.getEntriesSnapshot());
        } finally {
            lock.unlock();
        }
    }

    public Optional<ExecutionPlan> getExecutionPlan() {
        lock.lock();
        try {
            return Optional.ofNullable(executionPlan);
        } finally {
            lock.unlock();
        }
    }

    public void replaceExecutionPlan(ExecutionPlan plan) {
        lock.lock();
        try {
            executionPlan = Objects.requireNonNull(plan, "plan must not be null");
        } finally {
            lock.unlock();
        }
    }

    public void clearExecutionPlan() {
        lock.lock();
        try {
            executionPlan = null;
        } finally {
            lock.unlock();
        }
    }

    public Optional<ExecutionPlan> updateExecutionPlan(UnaryOperator<ExecutionPlan> updater) {
        Objects.requireNonNull(updater, "updater must not be null");
        lock.lock();
        try {
            if (executionPlan == null) {
                return Optional.empty();
            }
            executionPlan = Objects.requireNonNull(updater.apply(executionPlan), "updated plan must not be null");
            return Optional.of(executionPlan);
        } finally {
            lock.unlock();
        }
    }

    public Optional<Authentication> getAuthentication() {
        lock.lock();
        try {
            return Optional.ofNullable(authentication);
        } finally {
            lock.unlock();
        }
    }

    public Optional<LinterOutcome> getLastLinterOutcome() {
        lock.lock();
        try {
            return Optional.ofNullable(lastLinterOutcome);
        } finally {
            lock.unlock();
        }
    }

    public void setLastLinterOutcome(@Nullable LinterOutcome lastLinterOutcome) {
        lock.lock();
        try {
            this.lastLinterOutcome = lastLinterOutcome;
        } finally {
            lock.unlock();
        }
    }

    public Optional<OutputSchemaOutcome> getLastOutputSchemaOutcome() {
        lock.lock();
        try {
            return Optional.ofNullable(lastOutputSchemaOutcome);
        } finally {
            lock.unlock();
        }
    }

    public void setLastOutputSchemaOutcome(@Nullable OutputSchemaOutcome lastOutputSchemaOutcome) {
        lock.lock();
        try {
            this.lastOutputSchemaOutcome = lastOutputSchemaOutcome;
        } finally {
            lock.unlock();
        }
    }

    public void setAuthentication(@Nullable Authentication authentication) {
        lock.lock();
        try {
            this.authentication = authentication;
        } finally {
            lock.unlock();
        }
    }

    public void pushFrame(ExecutionFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        lock.lock();
        try {
            if (frames.size() >= maxDepth) {
                throw new BifrostStackOverflowException(sessionId, maxDepth, frame.route());
            }
            frames.push(frame);
        } finally {
            lock.unlock();
        }
    }

    public ExecutionFrame popFrame() {
        lock.lock();
        try {
            if (frames.isEmpty()) {
                throw new IllegalStateException("Cannot pop execution frame from an empty session stack.");
            }
            ExecutionFrame frame = frames.pop();
            toolActivityCountByFrameId.remove(frame.frameId());
            return frame;
        } finally {
            lock.unlock();
        }
    }

    public ExecutionFrame peekFrame() {
        lock.lock();
        try {
            if (frames.isEmpty()) {
                throw new IllegalStateException("Cannot peek execution frame from an empty session stack.");
            }
            return frames.peek();
        } finally {
            lock.unlock();
        }
    }

    @JsonProperty("frames")
    public List<ExecutionFrame> getFramesSnapshot() {
        lock.lock();
        try {
            return List.copyOf(frames);
        } finally {
            lock.unlock();
        }
    }

    @JsonIgnore
    public List<JournalEntry> getJournalSnapshot() {
        lock.lock();
        try {
            return executionJournal.getEntriesSnapshot();
        } finally {
            lock.unlock();
        }
    }

    @JsonProperty("executionJournal")
    public ExecutionJournal getExecutionJournal() {
        lock.lock();
        try {
            return new ExecutionJournal(executionJournal.getEntriesSnapshot());
        } finally {
            lock.unlock();
        }
    }

    @JsonProperty("executionPlan")
    public ExecutionPlan getExecutionPlanSnapshot() {
        lock.lock();
        try {
            return executionPlan;
        } finally {
            lock.unlock();
        }
    }

    @JsonProperty("lastLinterOutcome")
    public LinterOutcome getLastLinterOutcomeSnapshot() {
        lock.lock();
        try {
            return lastLinterOutcome;
        } finally {
            lock.unlock();
        }
    }

    @JsonProperty("lastOutputSchemaOutcome")
    public OutputSchemaOutcome getLastOutputSchemaOutcomeSnapshot() {
        lock.lock();
        try {
            return lastOutputSchemaOutcome;
        } finally {
            lock.unlock();
        }
    }

    public Optional<SessionUsageSnapshot> getSessionUsage() {
        lock.lock();
        try {
            return Optional.ofNullable(sessionUsage);
        } finally {
            lock.unlock();
        }
    }

    public void setSessionUsage(@Nullable SessionUsageSnapshot sessionUsage) {
        lock.lock();
        try {
            this.sessionUsage = sessionUsage;
        } finally {
            lock.unlock();
        }
    }

    public Optional<SessionUsageSnapshot> updateSessionUsage(UnaryOperator<SessionUsageSnapshot> updater) {
        Objects.requireNonNull(updater, "updater must not be null");
        lock.lock();
        try {
            if (sessionUsage == null) {
                return Optional.empty();
            }
            sessionUsage = Objects.requireNonNull(updater.apply(sessionUsage), "updated session usage must not be null");
            return Optional.of(sessionUsage);
        } finally {
            lock.unlock();
        }
    }

    @JsonProperty("sessionUsage")
    public SessionUsageSnapshot getSessionUsageSnapshot() {
        lock.lock();
        try {
            return sessionUsage;
        } finally {
            lock.unlock();
        }
    }

    public static BifrostSession getCurrentSession() {
        return BifrostSessionHolder.requireCurrentSession();
    }

    public void markToolActivityForCurrentFrame() {
        lock.lock();
        try {
            if (frames.isEmpty()) {
                return;
            }
            toolActivityCountByFrameId.merge(frames.peek().frameId(), 1, (current, increment) -> current + increment);
        } finally {
            lock.unlock();
        }
    }

    public int consumeToolActivityCountForCurrentFrame() {
        lock.lock();
        try {
            if (frames.isEmpty()) {
                return 0;
            }
            Integer count = toolActivityCountByFrameId.remove(frames.peek().frameId());
            return count == null ? 0 : count;
        } finally {
            lock.unlock();
        }
    }

    private void appendJournalEntry(Instant timestamp, JournalLevel level, JournalEntryType type, Object payload) {
        lock.lock();
        try {
            ExecutionFrame activeFrame = frames.peek();
            executionJournal.append(
                    timestamp,
                    level,
                    type,
                    payload,
                    activeFrame == null ? null : activeFrame.frameId(),
                    activeFrame == null ? null : activeFrame.route());
        } finally {
            lock.unlock();
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
