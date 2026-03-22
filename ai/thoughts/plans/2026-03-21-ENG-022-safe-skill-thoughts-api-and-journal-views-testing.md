# Safe Skill Thoughts API Testing Plan

## Change Summary
- Adding `frameId` and `route` execution context to `JournalEntry`.
- Updating `BifrostSession` to append these values organically from the active `ExecutionFrame`.
- Introducing `SkillThoughtTrace` and `SkillThoughtMapper` to safely sanitize trace output.
- Exposing `.getSkillThoughts(String route)` on `BifrostSession`.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntry.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionJournalTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionTest.java`

## Risk Assessment
- **Deserialization compatibility**: Older JSON journal snapshots stored in databases lack `frameId` and `route` fields. Modifying `JournalEntry` must be fully backwards compatible via Jackson `@JsonCreator` properties, or the application will throw exceptions when reading old data.
- **Data Leakage**: The mapper could accidentally return `JsonNode.asText()` or `JsonNode.toString()` which contains the full JSON structure (potentially leaking API keys or internal domain structures).
- **Edge cases**: `BifrostSession` may log items when the execution frame stack is completely empty (e.g. startup/shutdown events). The logging mechanism must tolerate an empty stack gracefully without throwing exceptions.

## Existing Test Coverage
- `ExecutionJournalTest.java`: Currently tests `JournalEntry` Jackson roundtrips and basic assertions.
- `BifrostSessionTest.java`: Tests that `pushFrame` and `logThought` work independently, but completely lacks any correlation tests between frames and journal logs.
- **Gaps**: No backwards-compatibility deserialization test for older snapshot JSON blocks. No trace filtering logic or sanitization tests.

## Bug Reproduction / Failing Test First
*Note: This is a new feature, but we must establish a backwards compatibility baseline first to prevent breaking existing snapshot persistence.*

- **Type**: unit
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionJournalTest.java`
- **Arrange/Act/Assert outline**:
  Create a literal JSON string simulating a baseline `JournalEntry` from a prior version (without `frameId` or `route`).
  Parse it using the `OBJECT_MAPPER`.
  Assert it parses successfully and sets the missing fields to `null`.
- **Expected failure (pre-fix)**: By explicitly adding `String frameId` and `String route` to the `JournalEntry` record without Jackson backwards compatibility annotations, deserialization of old snapshots will fail.

## Tests to Add/Update

### 1) deserializesOlderJournalsWithoutFrameId()
- Type: unit
- Location: `ExecutionJournalTest.java`
- What it proves: Jackson can deserialize journal strings from older application versions without throwing exceptions.
- Fixtures/data: Raw JSON string like `{"timestamp": "2026-03-15T12:00:00Z", "level": "INFO", "type": "THOUGHT", "payload": "testing"}`
- Mocks: None

### 2) executionJournalAppendsRetainFrameContext()
- Type: unit
- Location: `ExecutionJournalTest.java`
- What it proves: Validates that passing frame and route data into the newly modified `append` method results in those fields appearing in the snapshot.
- Fixtures/data: Basic `Instant` and `JournalLevel`.
- Mocks: None

### 3) sessionBindsFramesToJournalEntries()
- Type: unit
- Location: `BifrostSessionTest.java`
- What it proves: `BifrostSession.logThought` automatically injects the `frameId` and `route` based on the currently pushed `ExecutionFrame`.
- Fixtures/data: `ExecutionFrame` fixture.
- Mocks: None

### 4) filtersAndSanitizesSkillThoughtsByRoute()
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillThoughtTraceTest.java` (New)
- What it proves: `BifrostSession.getSkillThoughts("target.route")` returns ONLY thoughts for "target.route", and `TOOL_CALL` nodes are stripped down to simple strings rather than exposing full JSON maps.
- Fixtures/data: A mix of `THOUGHT` plain text entries, `TOOL_CALL` JSON map entries spanning different execution routes.
- Mocks: None

## How to Run
- `./mvnw clean test`

## Exit Criteria
- [ ] Failing test exists and fails pre-fix (when applicable)
- [x] All tests pass post-fix
- [x] New/updated tests cover the changed behavior and key edge cases
- [ ] Manual verification steps (if any) are complete
