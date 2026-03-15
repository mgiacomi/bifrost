package com.lokiscale.bifrost.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class BifrostSession {

    private final String sessionId;
    private final int maxDepth;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<ExecutionFrame> frames = new ArrayDeque<>();

    public BifrostSession(int maxDepth) {
        this(UUID.randomUUID().toString(), maxDepth);
    }

    public BifrostSession(String sessionId, int maxDepth) {
        this.sessionId = requireNonBlank(sessionId, "sessionId");
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be greater than zero");
        }
        this.maxDepth = maxDepth;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getMaxDepth() {
        return maxDepth;
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

    public List<ExecutionFrame> getFramesSnapshot() {
        lock.lock();
        try {
            return List.copyOf(frames);
        }
        finally {
            lock.unlock();
        }
    }

    public static BifrostSession getCurrentSession() {
        return BifrostSessionHolder.requireCurrentSession();
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
