# Safe Skill Thoughts API and Journal Views Implementation Plan

## Overview
Expose a safe, developer-friendly diagnostic view of skill thoughts built on top of the raw `ExecutionJournal`. This plan adds runtime context correlations (`frameId` and `route`) to the event history and introduces a new `SkillThoughtTrace` abstraction to project raw, potentially sensitive `JournalEntry` records into sanitized, readable summaries.

## Current State Analysis
Currently, `ExecutionJournal` acts as a chronological ledger for all serialized `JournalEntry` events (`THOUGHT`, `TOOL_CALL`, `ERROR`, etc.). The sequence has no correlation to the active runtime contexts (`ExecutionFrame`), meaning one cannot trivially query for thoughts restricted strictly to a specific skill or execution depth. Furthermore, journal payloads contain the full JSON responses from models or arbitrary Java objects which might expose sensitive or internal application structures if presented blindly to developers.

## Desired End State
The `BifrostSession` will expose an ergonomic `.getSkillThoughts(String route)` method returning a newly minted `SkillThoughtTrace` object. The execution journal will natively append every event with its originating context's `frameId` and `route`. Tool executions, results, linters, and errors will be selectively reduced into safe string descriptions, while the direct developer `THOUGHT` messages will pass through unscathed. These changes will not alter how the full journal is serialized behind the scenes.

### Key Discoveries:
- Stack context (`frames.peek()`) resides within `BifrostSession`, but is intentionally stripped when `ExecutionJournal.append()` creates the `JournalEntry`.
- Given that journals span versions and storage snapshots, `JournalEntry` must ensure new properties can deserialize gracefully on older journal blobs (using nullable `@JsonProperty` options).

## What We're NOT Doing
- We are not restructuring the underlying `JournalEntry` away from the `JsonNode` system.
- We are not discarding or changing what gets put into the primary `ExecutionJournal` for full snapshotting.
- We are not writing UI views, distributed tracing tools, or enforcing rate-limiting logic on thought retrieval.

## Implementation Approach
Update `JournalEntry` incrementally, utilizing `@JsonProperty` on construction for backwards compatibility, then inject the properties when `BifrostSession` interacts with the trace. Lastly, create `SkillThoughtTrace` and `SkillThought` abstractions to encapsulate the projection of `JournalEntryType` nodes down to explicit String logs.

## Phase 1: Contextual Journaling

### Overview
Enhance the existing `ExecutionJournal` and underlying `JournalEntry` to map events to an active `ExecutionFrame`.

### Changes Required:

#### 1. JournalEntry Enhancements
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntry.java`
**Changes**: Update the record to include `String frameId` and `String route`. Ensure `@JsonCreator` annotations or default constructors prevent errors reading older snapshot logs lacking these fields.

```java
// Add nullable fields or custom JsonCreator to handle backwards compatibility.
```

#### 2. ExecutionJournal Append Method
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java`
**Changes**: Update `append(...)` method signature to accept `@Nullable String frameId` and `@Nullable String route`. Create an overloaded append wrapper to handle any internal callers that lack frames if necessary.

#### 3. BifrostSession Appender Binding
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
**Changes**: Update `appendJournalEntry(...)` method to conditionally fetch `frames.peek()` if the stack is not empty, and feed `frameId` and `route` directly to `executionJournal.append()`.

### Success Criteria:

#### Automated Verification:
- [x] Project build/check passes: `./mvnw clean test`
- [x] `JournalEntry` fields map and serialize/deserialize successfully with Jackson correctly.
- [x] Existing `ExecutionJournalTest` passes.

#### Manual Verification:
- [ ] No regressions in test-driven API behavior.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Diagnostics Abstraction

### Overview
Introduce the `SkillThoughtTrace` abstraction to map internal json structures into readable formats.

### Changes Required:

#### 1. SkillThought and SkillThoughtTrace
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillThought.java` (New)  
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillThoughtTrace.java` (New)
**Changes**: Create DTOs mapping `timestamp`, `level`, and `content` (String redaction).

```java
public record SkillThought(Instant timestamp, JournalLevel level, String content) {}

public record SkillThoughtTrace(String route, List<SkillThought> thoughts) {}
```

#### 2. Redaction Mapper
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillThoughtMapper.java` (New)
**Changes**: Implement projection logic switching over `JournalEntryType`. `THOUGHT` maps content purely. `TOOL_CALL`, `ERROR`, `LINTER` strip `JsonNode` down to simple descriptive strings indicating tool execution/status.

### Success Criteria:

#### Automated Verification:
- [x] Project build/check passes: `./mvnw clean test`
- [x] Mapper logic strips JSON payloads correctly without leaking raw data.

#### Manual Verification:
- [ ] Manual review of mapper logic assures strings don't accidentally leak `JsonNode.asText()` containing raw unredacted data.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Session API & Verification

### Overview
Integrate the mapper abstraction into `BifrostSession` via `getSkillThoughts(String route)` and build explicit tests for diagnostics exposure.

### Changes Required:

#### 1. Session API Integration
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
**Changes**: Introduce public method `public SkillThoughtTrace getSkillThoughts(String route) {...}` that uses the mapper created in Phase 2 on the target subset of `ExecutionJournal.getEntriesSnapshot()`.

#### 2. Testing Execution Journal Views
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillThoughtTraceTest.java` (New)
**Changes**: Add testing that explicitly forces events through `logThought` and `logToolExecution`, then asserts the returned trace successfully masked the sensitive JSON map variables while returning the right content.

### Success Criteria:

#### Automated Verification:
- [x] Project build/check passes: `./mvnw clean test`
- [x] `SkillThoughtTraceTest` verifies that queries extract events spanning only the desired frame route.

#### Manual Verification:
- [ ] The return payload structure is readable without JSON string escaping madness.
- [ ] Output does not leak the execution context or variables.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:
- Serialization backwards compatibility for empty/missing `frameId` on `JournalEntry`.
- Specific projection tests ensuring the `SkillThoughtMapper` handles edge cases like blank error messages gracefully.

### Integration Tests:
- `BifrostSession` trace extraction with heavily nested/pushed recursive execution routes (verifying nested frame thoughts filter cleanly).

 **Note**: Prefer a dedicated testing plan artifact created via `/testing_plan` for full details (impacted areas, failing test first, commands to run, exit criteria). Keep this section as a high-level summary.

### Manual Testing Steps:
1. Examine a fully completed trace inside an example debugger context.
2. Ensure log lines provide coherent reading flow without raw dumps.

## Performance Considerations
String serialization maps across deeply nested `JsonNode` payloads on the fly for the trace representation. Because the trace creates a new object instead of retaining strong references, GC overhead should be transient. It uses the journal snapshot correctly.

## Migration Notes
Existing Journal records saved in previous database schema iterations will lack `frameId`. The string mapper logic should defensively expect null tracking identifiers and still avoid throwing NullPointerExceptions.

## References
- Original ticket/requirements: `ai/thoughts/tickets/eng-022-safe-skill-thoughts-api-and-journal-views.md`
- Related research: `ai/thoughts/research/2026-03-21-ENG-022-safe-skill-thoughts-api-and-journal-views.md`
