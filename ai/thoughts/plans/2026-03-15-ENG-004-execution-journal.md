# ENG-004 ExecutionJournal Implementation Plan

## Overview

Implement an `ExecutionJournal` event log inside the existing core session runtime so Bifrost can record reasoning, tool activity, and errors as JSON-serializable mission state. The work should extend the current `BifrostSession` model without regressing frame-stack behavior or the virtual-thread execution guarantees already established in the starter module.

## Current State Analysis

The runtime foundation already exists in the `bifrost-spring-boot-starter` module. `BifrostSession` currently owns mutable execution state through `sessionId`, `maxDepth`, a `ReentrantLock`, and a `Deque<ExecutionFrame>` but has no journal field, no append helpers, and no JSON-facing session state beyond its current getters ([BifrostSession.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java#L12)). The stack model is already snapshot-oriented, with all mutations guarded by `lock.lock()` and reads exposed through `List.copyOf(...)`, which is the strongest existing pattern to reuse for journal state ([BifrostSession.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java#L37)).

Session lifecycle and thread scoping are already in place. `BifrostSessionRunner` creates sessions and binds them through `BifrostSessionHolder`, while tests verify distinct sessions and isolated state across `Executors.newVirtualThreadPerTaskExecutor()` ([BifrostSessionRunner.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java#L15), [BifrostSessionRunnerTest.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionRunnerTest.java#L16)). Jackson is already available in the starter module, so the main missing pieces are the journal domain types, serialization-friendly constructors or annotations, and tests proving round-trip persistence and virtual-thread-safe mutation ([bifrost-spring-boot-starter/pom.xml](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/pom.xml#L18)).

The design docs also show that this journal is intended to live on `BifrostSession` as the persistence-friendly "thinking log" that later coordinator work will update, which means the implementation should expose a clean core API now without taking a dependency on future `ExecutionCoordinator` code ([README.md](/C:/opendev/code/bifrost/ai/thoughts/phases/README.md#L57), [phase2.md](/C:/opendev/code/bifrost/ai/thoughts/phases/phase2.md#L34)).

## Desired End State

`BifrostSession` owns both its existing frame stack and a new `ExecutionJournal` that accumulates sequential `JournalEntry` events for thoughts, tool calls/results, and errors. Journal payloads are accepted ergonomically at the session API boundary but converted immediately into a JSON-stable representation so the journal becomes a durable snapshot rather than a bag of live POJOs. Both the journal alone and the full session object can be serialized to JSON and deserialized back into valid Java objects with stable ordering and payload fidelity. Coordinator-facing append helpers exist on the session API so future orchestration code can log events with minimal ceremony, and the added synchronization strategy remains compatible with the project's existing virtual-thread execution model.

### Key Discoveries:
- `BifrostSession` already centralizes mutable runtime state behind a single `ReentrantLock`, making it the natural place to attach journal state rather than introducing a separate holder object ([BifrostSession.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java#L12)).
- Existing session tests already use immutable snapshots as the public read model, so `ExecutionJournal` should follow that same API style instead of exposing its internal collection directly ([BifrostSessionTest.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java#L65)).
- Virtual-thread isolation is already a documented runtime contract, so ENG-004 must extend those tests rather than relying on lock choice alone as proof ([BifrostSessionRunnerTest.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionRunnerTest.java#L30)).
- Historical specs tie the journal to future mission persistence and coordinator progress tracking, which argues for eager conversion into JSON-stable payloads now rather than storing coordinator-specific DTOs or arbitrary POJO references ([README.md](/C:/opendev/code/bifrost/ai/thoughts/phases/README.md#L47)).

## What We're NOT Doing

- Implementing `ExecutionCoordinator`, planning-mode task updates, or any coordinator workflow beyond the helper methods ENG-004 explicitly requires.
- Adding JDBC, Redis, or any other persistence backend. This plan only makes session/journal state serialization-ready.
- Refactoring the current `ThreadLocal`-based session binding model or replacing the existing frame stack implementation.
- Introducing metrics, Micrometer instrumentation, or external telemetry exporters.
- Designing a broad event taxonomy beyond the types needed for ENG-004 and the immediate follow-on coordinator work.

## Implementation Approach

Add the journal as another core session-owned state object and keep the public API intentionally small: journal model types for data shape, session-level helper methods for writes, and immutable snapshot accessors for reads. Accept `Object` payloads in helper methods for developer ergonomics, but eagerly convert them to `JsonNode` or an equivalent Jackson-owned tree representation when the entry is appended so the journal remains classpath-independent and serialization-safe. For serialization, treat runtime-only lifecycle objects such as `ReentrantLock` as transient state: ignore them in JSON and rehydrate fresh instances when a session is deserialized. For concurrency, reuse the existing lock discipline around session mutation so frame and journal state cannot diverge under concurrent access; for this MVP, the strict total ordering benefit outweighs the negligible contention of in-memory journal appends relative to LLM latency. Prove the behavior with the dedicated testing plan at [2026-03-15-ENG-004-execution-journal-testing.md](/C:/opendev/code/bifrost/ai/thoughts/plans/2026-03-15-ENG-004-execution-journal-testing.md).

## Phase 1: Add Journal Domain Types

### Overview
Introduce the new event-log model in the `core` package with serialization-friendly structure and immutable read semantics.

### Changes Required:

#### 1. Journal entry model
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntry.java`
**Changes**: Add a new core value type representing one journal event with `timestamp`, `level`, `type`, and payload. Normalize null handling and store payload in a JSON-stable representation so plain text and structured content survive round-trip serialization without relying on polymorphic POJO restoration.

```java
public record JournalEntry(
        Instant timestamp,
        JournalLevel level,
        JournalEntryType type,
        JsonNode payload) {

    public JournalEntry {
        timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        level = Objects.requireNonNull(level, "level must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        payload = Objects.requireNonNull(payload, "payload must not be null");
    }
}
```

#### 2. Journal container and enums
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java`
**Changes**: Add a small container around a list of entries with append and snapshot behavior. Add `JournalEntryType` and `JournalLevel` enums in the same package if that keeps the domain model clearer and avoids stringly typed event metadata. The append path should own payload conversion so entries are normalized the moment they cross into journal state.

```java
public final class ExecutionJournal {

    private final ObjectMapper objectMapper;
    private final List<JournalEntry> entries;

    @JsonCreator
    public ExecutionJournal(@JsonProperty("entries") List<JournalEntry> entries) {
        this.objectMapper = new ObjectMapper();
        this.entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    }

    public void append(Instant timestamp, JournalLevel level, JournalEntryType type, Object payload) {
        entries.add(new JournalEntry(
                timestamp,
                level,
                type,
                objectMapper.valueToTree(Objects.requireNonNull(payload, "payload must not be null"))));
    }

    public List<JournalEntry> getEntriesSnapshot() {
        return List.copyOf(entries);
    }
}
```

### Success Criteria:

#### Automated Verification:
- [x] The new core domain types compile successfully: `mvn -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Initial journal tests can be added and fail for missing session integration: `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostSessionTest test`

#### Manual Verification:
- [ ] The model shape is readable and consistent with the existing `ExecutionFrame` style.
- [ ] Event metadata is generic enough for future coordinator use without requiring a redesign.
- [ ] Payload handling is simple enough for both string and structured JSON-like data while remaining independent of caller POJO types after append.
- [ ] No out-of-scope persistence or coordinator concerns have leaked into the model layer.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual review of the model shape was successful before proceeding to the next phase.

---

## Phase 2: Integrate the Journal into BifrostSession

### Overview
Attach the journal directly to `BifrostSession`, expose append helpers, and keep state mutation consistent with the existing lock-guarded session runtime.

### Changes Required:

#### 1. Session state and API expansion
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
**Changes**: Add an `ExecutionJournal` field, initialize it in both constructors, expose a snapshot getter, and add helper methods for thought/tool/error logging. Reuse the existing `ReentrantLock` so stack state and journal state follow one concurrency model. Treat the lock as lifecycle-only state that is ignored during JSON serialization and recreated during deserialization.

```java
public final class BifrostSession {

    @JsonIgnore
    private final ReentrantLock lock;
    private final ExecutionJournal executionJournal;

    public BifrostSession(String sessionId, int maxDepth) {
        this(sessionId, maxDepth, List.of(), new ExecutionJournal(null));
    }

    @JsonCreator
    public BifrostSession(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("maxDepth") int maxDepth,
            @JsonProperty("frames") List<ExecutionFrame> frames,
            @JsonProperty("executionJournal") ExecutionJournal executionJournal) {
        // validate, hydrate frames, store journal, rehydrate lock
    }

    public void logThought(Instant timestamp, Object payload) { ... }
    public void logToolExecution(Instant timestamp, Object payload) { ... }
    public void logError(Instant timestamp, Object payload) { ... }
    public List<JournalEntry> getJournalSnapshot() { ... }
}
```

#### 2. Session tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
**Changes**: Extend the existing session test suite with the failing-test-first case, ordered append assertions, and immutable journal snapshot coverage while leaving frame-stack coverage intact.

```java
@Test
void appendsJournalEntriesInSequentialOrder() {
    BifrostSession session = new BifrostSession("session-1", 4);

    session.logThought(Instant.parse("2026-03-15T12:00:00Z"), "plan");
    session.logToolExecution(Instant.parse("2026-03-15T12:00:01Z"), Map.of("route", "tool.run"));
    session.logError(Instant.parse("2026-03-15T12:00:02Z"), "boom");

    assertThat(session.getJournalSnapshot())
            .extracting(JournalEntry::type)
            .containsExactly(THOUGHT, TOOL_CALL, ERROR);
}
```

### Success Criteria:

#### Automated Verification:
- [x] Session integration compiles and the session test suite passes: `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostSessionTest test`
- [x] Existing frame-stack behavior still passes unchanged: `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostSessionTest,BifrostSessionHolderTest test`

#### Manual Verification:
- [ ] The session API is still small and understandable for future coordinator callers.
- [ ] Journal writes do not weaken or complicate the existing frame-stack API.
- [ ] Constructor behavior remains sensible for both runtime-created and Jackson-created sessions, including fresh lock rehydration.
- [ ] Helper naming and event typing read clearly as coordinator-facing primitives.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Prove JSON Round-Trip and Virtual-Thread Behavior

### Overview
Finish the feature by proving the persistence contract and concurrency behavior described in the ticket acceptance criteria.

### Changes Required:

#### 1. Journal and session JSON tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionJournalTest.java`
**Changes**: Add focused ObjectMapper tests covering mixed event payloads and round-trip reconstruction of journal state, including proof that eager payload conversion preserves inspectable JSON without requiring original POJO classes during restore.

```java
@Test
void roundTripsExecutionJournalThroughJackson() throws Exception {
    ExecutionJournal journal = new ExecutionJournal(null);
    journal.append(Instant.parse("2026-03-15T12:00:00Z"), INFO, THOUGHT, Map.of("message", "draft plan"));

    String json = new ObjectMapper().writeValueAsString(journal);
    ExecutionJournal restored = new ObjectMapper().readValue(json, ExecutionJournal.class);

    assertThat(restored.getEntriesSnapshot()).containsExactlyElementsOf(journal.getEntriesSnapshot());
}
```

#### 2. Session serialization and virtual-thread regression coverage
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionJsonTest.java`
**Changes**: Add a session round-trip test that includes both frames and journal entries and proves a deserialized session gets a fresh usable lock. Extend `BifrostSessionRunnerTest` with concurrent journal mutation assertions to prove session isolation still holds when the new journal state is exercised.

```java
Future<String> first = executor.submit(() -> sessionRunner.callWithNewSession(session -> {
    session.logThought(Instant.parse("2026-03-15T12:00:00Z"), "first");
    return session.getSessionId() + ":" + session.getJournalSnapshot().size();
}));
```

### Success Criteria:

#### Automated Verification:
- [x] Journal JSON tests pass: `mvn -pl bifrost-spring-boot-starter -Dtest=ExecutionJournalTest,BifrostSessionJsonTest test`
- [x] Virtual-thread isolation tests pass with journal mutation coverage: `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostSessionRunnerTest test`
- [x] Full starter-module test suite passes: `mvn -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] Serialized session JSON is readable enough to serve as future save-game state.
- [ ] The JSON shape clearly contains journal ordering and payload data without hidden framework artifacts.
- [ ] The added concurrency coverage is convincing for the current virtual-thread usage model.
- [ ] No test-only accommodations leaked into production APIs unnecessarily.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for final human confirmation that the JSON shape and runtime behavior are acceptable before closing the ticket.

---

## Testing Strategy

### Unit Tests:
- Add session-level tests for sequential journal append behavior and immutable journal snapshots.
- Add focused `ExecutionJournal` JSON round-trip tests for mixed payload shapes and enum/timestamp fidelity.
- Add a session JSON round-trip test that verifies both the existing frame stack and the new journal survive serialization.

### Integration Tests:
- Extend the current virtual-thread session-runner tests so concurrent sessions append to their own journals and keep isolated state.

**Note**: Use the dedicated testing artifact at [2026-03-15-ENG-004-execution-journal-testing.md](/C:/opendev/code/bifrost/ai/thoughts/plans/2026-03-15-ENG-004-execution-journal-testing.md) for the detailed failing-test-first plan, impacted areas, and exact exit criteria.

### Manual Testing Steps:
1. Inspect a serialized `BifrostSession` instance and confirm it includes `sessionId`, `maxDepth`, existing frame state, and ordered journal entries.
2. Compare a journal entry created from a string payload with one created from a structured map payload and confirm both shapes are understandable in JSON.
3. Review the session API after implementation and confirm a future `ExecutionCoordinator` could log thoughts, tool calls, and errors without extra adaptation code.

## Performance Considerations

The feature should stay inside the current session-level lock model rather than adding separate locks or synchronized blocks. That keeps the concurrency story simple and aligns with the project's existing virtual-thread guidance. For MVP, the strict total ordering benefit is more valuable than optimizing contention because journal appends remain in-memory and are trivial relative to LLM network latency. Journal payloads should remain lightweight and JSON-friendly; convert them eagerly into Jackson-owned tree data when they enter the journal rather than preserving arbitrary live object graphs.

## Migration Notes

There is no live persistence layer yet, so there is no data migration to perform. The main compatibility concern is constructor and serialization design: runtime-created sessions should still be easy to instantiate directly, while Jackson-created sessions should be able to restore both stack and journal state for future persistence work and rehydrate fresh lifecycle state such as the session lock.

## References

- Original ticket/requirements: `ai/thoughts/tickets/eng-004-execution-journal.md`
- Related research: `ai/thoughts/research/2026-03-15-ENG-004-execution-journal-current-state.md`
- Related testing plan: `ai/thoughts/plans/2026-03-15-ENG-004-execution-journal-testing.md`
- Current session implementation: [BifrostSession.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java#L12)
- Existing virtual-thread coverage: [BifrostSessionRunnerTest.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionRunnerTest.java#L16)
- Historical session/journal intent: [README.md](/C:/opendev/code/bifrost/ai/thoughts/phases/README.md#L57)
