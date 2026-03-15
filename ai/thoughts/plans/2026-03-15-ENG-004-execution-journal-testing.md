# ENG-004 ExecutionJournal Testing Plan

## Change Summary
- Add an `ExecutionJournal` event log to the core runtime so `BifrostSession` can capture reasoning, tool activity, and errors alongside the existing execution-frame stack.
- Add JSON-safe journal models and session-facing append helpers such as `logThought(..)`, `logToolExecution(..)`, and `logError(..)` that work cleanly in the current virtual-thread-based execution model.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
  Current session state only stores `sessionId`, `maxDepth`, a `ReentrantLock`, and the frame stack, so journal state and helper APIs will extend this class directly.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/`
  New journal model types should live next to the existing session/runtime types in the core package to match the current module layout.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
  This is the main pattern for session state assertions and should absorb new session-level journal coverage.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionRunnerTest.java`
  This already proves virtual-thread isolation, so it is the right place to extend concurrency coverage for journal appends.

## Risk Assessment
- Session construction regressions: adding journal state must not change the current constructor guarantees or break existing frame-stack behavior.
- Ordering regressions: journal entries must remain sequential and stable, especially if append helpers share the same lock strategy as frame operations.
- Serialization regressions: JSON round-trip coverage needs to prove that timestamps, enums, and eagerly converted JSON payloads deserialize back into valid domain objects without losing data or depending on original POJO classes.
- Concurrency regressions: journal logging must not introduce virtual-thread pinning or cross-session state leakage when multiple sessions are created concurrently.
- Payload-shape drift: helper methods may serialize tool inputs/results differently than intended unless tests pin down representative map/string payloads early.

## Existing Test Coverage
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java` already covers constructor behavior, stack mutation ordering, overflow handling, and immutable snapshots, which gives a natural home for journal snapshot and helper-method tests.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionRunnerTest.java` already uses `Executors.newVirtualThreadPerTaskExecutor()` to assert per-session isolation across concurrent virtual threads.
- `bifrost-spring-boot-starter/pom.xml` already includes `jackson-databind` plus Spring Boot test support, so no new test-only JSON dependency should be needed for round-trip coverage.
- Gap: there is currently no test exercising `ExecutionJournal`, `JournalEntry`, or JSON serialization of runtime session state.

## Bug Reproduction / Failing Test First
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
- Arrange/Act/Assert outline:
  1. Create a `BifrostSession`.
  2. Append a thought entry, a tool-execution entry, and an error entry through session helpers.
  3. Assert the journal snapshot contains three entries in append order with the expected types, levels, and payload values.
- Expected failure (pre-fix):
  The test will not compile because `BifrostSession` currently exposes no journal field, no append helpers, and no journal snapshot API.

## Tests to Add/Update
### 1) `appendsJournalEntriesInSequentialOrder`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
- What it proves:
  Session helper methods append entries in order and preserve the timestamp/type/level/payload contract required by ENG-004.
- Fixtures/data:
  Fixed `Instant` values and small representative payloads such as a thought string and a tool payload map containing route and arguments.
- Mocks:
  None.

### 2) `exposesImmutableJournalSnapshots`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`
- What it proves:
  Journal reads follow the same immutable snapshot pattern as frame snapshots so callers cannot mutate internal session state.
- Fixtures/data:
  A session with one appended journal entry.
- Mocks:
  None.

### 3) `roundTripsExecutionJournalThroughJackson`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionJournalTest.java`
- What it proves:
  A journal containing mixed entry types and structured payloads can be serialized to JSON and deserialized back into equivalent objects with payloads preserved as JSON tree data rather than caller-specific POJOs.
- Fixtures/data:
  `ObjectMapper`, fixed `Instant`, enum values, one string payload, and one structured payload map.
- Mocks:
  None.

### 4) `roundTripsSessionWithEmbeddedJournalThroughJackson`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionJsonTest.java`
- What it proves:
  The actual persistence target is the session, so a serialized `BifrostSession` retains both frame-stack and journal state after deserialization and rehydrates a fresh usable lock instead of trying to serialize lock state.
- Fixtures/data:
  Session with a known `sessionId`, one frame, and multiple journal entries.
- Mocks:
  None.

### 5) `isolatesJournalMutationAcrossConcurrentVirtualThreads`
- Type: integration-ish unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionRunnerTest.java`
- What it proves:
  Concurrent virtual-thread sessions can append to their own journals without leaking entries or corrupting sequence/order across sessions.
- Fixtures/data:
  Two `callWithNewSession` tasks, each appending distinct journal entries and returning a session/journal summary for assertion.
- Mocks:
  None.

### 6) `supportsStructuredPayloadsWithoutCustomFixtureTypes`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionJournalTest.java`
- What it proves:
  The chosen payload representation can handle plain strings and JSON-like maps without forcing test code to invent extra DTOs, and the journal stores them as inspectable JSON tree content at append time.
- Fixtures/data:
  Map payloads that mimic future tool-call inputs/results.
- Mocks:
  None.

### 7) `rehydratesFreshLockAfterSessionDeserialization`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionJsonTest.java`
- What it proves:
  A deserialized session can still accept journal and frame mutations, proving transient lifecycle state such as `ReentrantLock` is recreated correctly.
- Fixtures/data:
  Round-tripped session JSON followed by post-deserialization calls to `logThought(...)` and `pushFrame(...)`.
- Mocks:
  None.

## How to Run
- Full module verification: `mvn -pl bifrost-spring-boot-starter test`
- Focused session test pass while iterating: `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostSessionTest,BifrostSessionRunnerTest,ExecutionJournalTest,BifrostSessionJsonTest test`
- If JSON mapping becomes constructor-sensitive, rerun the focused suite after any annotation or constructor change so serialization and lock-rehydration regressions surface immediately.

## Exit Criteria
- [x] A failing session-level test is added first and proves the journal API is missing or incorrect before implementation.
- [x] `BifrostSessionTest` covers ordered append behavior plus immutable journal snapshots.
- [x] Dedicated JSON round-trip tests prove both `ExecutionJournal` alone and `BifrostSession` with embedded journal state serialize and deserialize successfully.
- [x] JSON tests prove payloads are normalized into JSON tree data at append time rather than restored as caller POJO types.
- [x] Session JSON tests prove transient lifecycle state such as `ReentrantLock` is rehydrated after deserialization.
- [x] Virtual-thread concurrency coverage proves per-session journal isolation and no shared-state leakage.
- [x] `mvn -pl bifrost-spring-boot-starter test` passes after the implementation is complete.
- [ ] Manual spot-check of the serialized JSON confirms the shape is suitable for later JDBC/Redis persistence, especially `timestamp`, `level`, `type`, and payload content.
