package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lokiscale.bifrost.linter.LinterOutcome;
import com.lokiscale.bifrost.outputschema.OutputSchemaOutcome;
import com.lokiscale.bifrost.runtime.trace.DefaultExecutionTraceHandle;
import com.lokiscale.bifrost.runtime.trace.ExecutionJournalProjector;
import com.lokiscale.bifrost.runtime.usage.SessionUsageSnapshot;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class BifrostSession {

    private static final Clock DEFAULT_CLOCK = Clock.systemUTC();

    private final String sessionId;
    private final int maxDepth;
    @JsonIgnore
    private final ReentrantLock lock;
    @JsonIgnore
    private final Deque<ExecutionFrame> frames;
    @JsonIgnore
    private final Map<String, Integer> toolActivityCountByFrameId;
    @JsonIgnore
    private final @Nullable ExecutionTraceHandle executionTraceHandle;
    @JsonIgnore
    private final ExecutionJournalProjector journalProjector;
    @JsonIgnore
    private ExecutionJournal finalizedExecutionJournal;
    private ExecutionPlan executionPlan;
    private LinterOutcome lastLinterOutcome;
    private OutputSchemaOutcome lastOutputSchemaOutcome;
    private SessionUsageSnapshot sessionUsage;
    @JsonIgnore
    private Authentication authentication;

    public BifrostSession(int maxDepth) {
        this(UUID.randomUUID().toString(), maxDepth);
    }

    BifrostSession(String sessionId, int maxDepth) {
        this(sessionId, maxDepth, List.of(), null, null, null, null, null, TracePersistencePolicy.ONERROR, DEFAULT_CLOCK);
    }

    public BifrostSession(int maxDepth, @Nullable Authentication authentication) {
        this(UUID.randomUUID().toString(), maxDepth, List.of(), null, null, null, null, authentication, TracePersistencePolicy.ONERROR, DEFAULT_CLOCK);
    }

    BifrostSession(String sessionId, int maxDepth, @Nullable Authentication authentication) {
        this(sessionId, maxDepth, List.of(), null, null, null, null, authentication, TracePersistencePolicy.ONERROR, DEFAULT_CLOCK);
    }

    BifrostSession(String sessionId,
                   int maxDepth,
                   @Nullable Authentication authentication,
                   TracePersistencePolicy persistencePolicy) {
        this(sessionId, maxDepth, authentication, persistencePolicy, DEFAULT_CLOCK);
    }

    BifrostSession(String sessionId,
                   int maxDepth,
                   @Nullable Authentication authentication,
                   TracePersistencePolicy persistencePolicy,
                   Clock clock) {
        this(sessionId, maxDepth, List.of(), null, null, null, null, authentication, persistencePolicy, clock);
    }

    BifrostSession(
            String sessionId,
            int maxDepth,
            List<ExecutionFrame> frames,
            ExecutionPlan executionPlan,
            @Nullable LinterOutcome lastLinterOutcome,
            @Nullable OutputSchemaOutcome lastOutputSchemaOutcome,
            @Nullable SessionUsageSnapshot sessionUsage,
            @Nullable Authentication authentication,
            TracePersistencePolicy persistencePolicy,
            Clock clock) {
        this.sessionId = requireNonBlank(sessionId, "sessionId");
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be greater than zero");
        }
        this.maxDepth = maxDepth;
        this.lock = new ReentrantLock();
        this.frames = new ArrayDeque<>(frames == null ? List.of() : List.copyOf(frames));
        this.toolActivityCountByFrameId = new HashMap<>();
        // The runtime supports one session lifecycle: a live in-process session with a canonical trace handle.
        this.journalProjector = new ExecutionJournalProjector();
        this.executionTraceHandle = new DefaultExecutionTraceHandle(sessionId, persistencePolicy, clock);
        this.finalizedExecutionJournal = null;
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
            if (countsTowardMaxDepth(frame) && currentMaxDepthUsage() >= maxDepth) {
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
        return getExecutionJournal().getEntriesSnapshot();
    }

    @JsonProperty("executionTrace")
    public ExecutionTrace getExecutionTrace() {
        lock.lock();
        try {
            return requireExecutionTraceHandle().snapshot();
        } finally {
            lock.unlock();
        }
    }

    @JsonIgnore
    public ExecutionJournal getExecutionJournal() {
        lock.lock();
        try {
            if (finalizedExecutionJournal != null) {
                return finalizedExecutionJournal;
            }
            return journalProjector.project(requireExecutionTraceHandle());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to project execution journal for session '" + sessionId + "'", ex);
        } finally {
            lock.unlock();
        }
    }

    @JsonProperty("executionJournal")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ExecutionJournal getSerializedExecutionJournal() {
        lock.lock();
        try {
            return finalizedExecutionJournal != null ? finalizedExecutionJournal : getExecutionJournal();
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
            toolActivityCountByFrameId.merge(frames.peek().frameId(), 1, Integer::sum);
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

    public void markTraceErrored() {
        lock.lock();
        try {
            requireExecutionTraceHandle().markErrored();
        } finally {
            lock.unlock();
        }
    }

    public void finalizeTrace(Map<String, Object> metadata) {
        lock.lock();
        ExecutionJournal projectedJournal = null;
        IOException projectionFailure = null;
        IOException finalizationFailure = null;
        try {
            ExecutionTraceHandle handle = requireExecutionTraceHandle();
            if (handle.snapshot().completed() && finalizedExecutionJournal != null) {
                return;
            }
            try {
                projectedJournal = journalProjector.project(handle);
            }
            catch (IOException ex) {
                projectionFailure = ex;
            }
            try {
                handle.finalizeTrace(metadata == null ? Map.of() : Map.copyOf(metadata));
            }
            catch (IOException ex) {
                finalizationFailure = ex;
            }
            if (finalizationFailure == null && projectionFailure == null && projectedJournal != null) {
                finalizedExecutionJournal = projectedJournal;
            }
        } finally {
            lock.unlock();
        }
        if (finalizationFailure != null) {
            if (projectionFailure != null) {
                finalizationFailure.addSuppressed(projectionFailure);
            }
            throw new IllegalStateException("Failed to finalize execution trace for session '" + sessionId + "'", finalizationFailure);
        }
        if (projectionFailure != null) {
            throw new IllegalStateException("Failed to finalize execution trace for session '" + sessionId + "'", projectionFailure);
        }
    }

    public void readTraceRecords(Consumer<TraceRecord> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        lock.lock();
        try {
            requireExecutionTraceHandle().readRecords(consumer);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read execution trace for session '" + sessionId + "'", ex);
        } finally {
            lock.unlock();
        }
    }

    void appendTraceRecord(TraceRecordType type, Map<String, Object> metadata, Object payload) {
        appendTrace(type, metadata == null ? Map.of() : Map.copyOf(metadata), payload);
    }

    void appendTraceRecord(TraceRecordType type, ExecutionFrame frame, Map<String, Object> metadata, Object payload) {
        Objects.requireNonNull(frame, "frame must not be null");
        lock.lock();
        try {
            ExecutionTraceHandle handle = requireExecutionTraceHandle();
            handle.append(type, frame, frame.traceFrameType(), metadata == null ? Map.of() : Map.copyOf(metadata), payload);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to append execution trace record for session '" + sessionId + "'", ex);
        } finally {
            lock.unlock();
        }
    }

    private void appendTrace(TraceRecordType type, Map<String, Object> metadata, Object payload) {
        lock.lock();
        try {
            ExecutionTraceHandle handle = requireExecutionTraceHandle();
            ExecutionFrame activeFrame = frames.peek();
            TraceFrameType frameType = activeFrame == null ? null : activeFrame.traceFrameType();
            if (activeFrame == null) {
                handle.append(type, metadata == null ? Map.of() : Map.copyOf(metadata), payload);
            } else {
                handle.append(type, activeFrame, frameType, metadata == null ? Map.of() : Map.copyOf(metadata), payload);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to append execution trace record for session '" + sessionId + "'", ex);
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

    private int currentMaxDepthUsage() {
        int depth = 0;
        for (ExecutionFrame frame : frames) {
            if (countsTowardMaxDepth(frame)) {
                depth++;
            }
        }
        return depth;
    }

    private static boolean countsTowardMaxDepth(ExecutionFrame frame) {
        return frame != null && switch (frame.traceFrameType()) {
            case MODEL_CALL, PLANNING, TOOL_INVOCATION -> false;
            default -> true;
        };
    }

    private ExecutionTraceHandle requireExecutionTraceHandle() {
        if (executionTraceHandle == null) {
            throw new IllegalStateException("BifrostSession requires a live execution trace handle");
        }
        return executionTraceHandle;
    }
}
