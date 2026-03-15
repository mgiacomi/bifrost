package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class BifrostSession {

    private final String sessionId;
    private final int maxDepth;
    @JsonIgnore
    private final ReentrantLock lock;
    @JsonIgnore
    private final Deque<ExecutionFrame> frames;
    private final ExecutionJournal executionJournal;

    public BifrostSession(int maxDepth) {
        this(UUID.randomUUID().toString(), maxDepth);
    }

    public BifrostSession(String sessionId, int maxDepth) {
        this(sessionId, maxDepth, List.of(), new ExecutionJournal());
    }

    @JsonCreator
    public BifrostSession(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("maxDepth") int maxDepth,
            @JsonProperty("frames") List<ExecutionFrame> frames,
            @JsonProperty("executionJournal") ExecutionJournal executionJournal) {
        this.sessionId = requireNonBlank(sessionId, "sessionId");
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be greater than zero");
        }
        this.maxDepth = maxDepth;
        this.lock = new ReentrantLock();
        this.frames = new ArrayDeque<>(frames == null ? List.of() : List.copyOf(frames));
        this.executionJournal = executionJournal == null ? new ExecutionJournal() : executionJournal;
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

    public void logError(Instant timestamp, Object payload) {
        appendJournalEntry(timestamp, JournalLevel.ERROR, JournalEntryType.ERROR, payload);
    }

    public void pushFrame(ExecutionFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        lock.lock();
        try {
            if (frames.size() >= maxDepth) {
                throw new BifrostStackOverflowException(sessionId, maxDepth, frame.route());
            }
            frames.push(frame);
        }
        finally {
            lock.unlock();
        }
    }

    public ExecutionFrame popFrame() {
        lock.lock();
        try {
            if (frames.isEmpty()) {
                throw new IllegalStateException("Cannot pop execution frame from an empty session stack.");
            }
            return frames.pop();
        }
        finally {
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
        }
        finally {
            lock.unlock();
        }
    }

    @JsonProperty("frames")
    public List<ExecutionFrame> getFramesSnapshot() {
        lock.lock();
        try {
            return List.copyOf(frames);
        }
        finally {
            lock.unlock();
        }
    }

    @JsonIgnore
    public List<JournalEntry> getJournalSnapshot() {
        lock.lock();
        try {
            return executionJournal.getEntriesSnapshot();
        }
        finally {
            lock.unlock();
        }
    }

    @JsonProperty("executionJournal")
    public ExecutionJournal getExecutionJournal() {
        lock.lock();
        try {
            return new ExecutionJournal(executionJournal.getEntriesSnapshot());
        }
        finally {
            lock.unlock();
        }
    }

    public static BifrostSession getCurrentSession() {
        return BifrostSessionHolder.requireCurrentSession();
    }

    private void appendJournalEntry(Instant timestamp, JournalLevel level, JournalEntryType type, Object payload) {
        lock.lock();
        try {
            executionJournal.append(timestamp, level, type, payload);
        }
        finally {
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
