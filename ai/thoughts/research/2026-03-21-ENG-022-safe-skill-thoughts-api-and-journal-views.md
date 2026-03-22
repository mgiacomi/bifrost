---
date: 2026-03-21T18:40:17-07:00
researcher: Antigravity
git_commit: 1f1f2b4e40c0daa96e5eb2fd31107791a25393c8
branch: main
repository: bifrost
topic: "Safe Skill Thoughts API and Journal Views"
tags: [research, codebase, execution-journal, bifrost-session, journal-entry]
status: complete
last_updated: 2026-03-21T18:44:31-07:00
last_updated_by: Antigravity
last_updated_note: "Added follow-up research for implementation suggestions"
---

# Research: Safe Skill Thoughts API and Journal Views

**Date**: 2026-03-21T18:40:17-07:00
**Researcher**: Antigravity
**Git Commit**: 1f1f2b4e40c0daa96e5eb2fd31107791a25393c8
**Branch**: main
**Repository**: bifrost

## Research Question
What is the current implementation of `ExecutionJournal`, `JournalEntry`, and related session structures for recording skill thoughts? The goal is to provide context for implementing a safe, developer-friendly API for extracting readable thought sequences from these components.

## Summary
The current codebase leverages a session-based recording mechanism via `BifrostSession` and `ExecutionJournal`. Journal entries are sequentially appended to a list wrapped by `ExecutionJournal` without any built-in abstraction for filtering or structuring by specific skill/route. Journal contents are represented as generic `JsonNode` payloads alongside a timestamp, log level, and predefined entry types (e.g., `THOUGHT`, `TOOL_CALL`). Stack frames (`ExecutionFrame`) representing active route contexts exist in the session but are not directly correlated into the payload structure of the generic `JournalEntry`.

## Detailed Findings

### ExecutionJournal
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java`
- Acts as a wrapper over a `List<JournalEntry>`.
- The primary method is `append(Instant timestamp, JournalLevel level, JournalEntryType type, Object payload)`, which adds a new journal entry after converting the payload to a generic Jackson `JsonNode`.
- Exposes `getEntriesSnapshot()`, which returns a read-only unmodifiable copy of the current entries.
- It does not contain search or filtering query methods.

### JournalEntry
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntry.java`
- Defined as a Java record storing:
  - `Instant timestamp`
  - `JournalLevel level`
  - `JournalEntryType type`
  - `JsonNode payload`
- The payload contains raw data representing a varied set of logs, depending on the event type (e.g., plain text for `THOUGHT`, JSON object structures for `TOOL_CALL`, `PLAN_CREATED`).

### BifrostSession
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- Manages the single `ExecutionJournal` instance for the current execution context.
- Provides specific helper methods for logging distinct lifecycle events: `logThought`, `logToolExecution`, `logToolResult`, `logError`, etc.
- Provides `getJournalSnapshot()` mapping to `executionJournal.getEntriesSnapshot()` which exposes the complete sequence of raw events.
- Maintains a stack (`Deque<ExecutionFrame> frames`) of `ExecutionFrame` records indicating current execution depth/route logic via `pushFrame(ExecutionFrame)` and `popFrame()`.

### ExecutionFrame
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionFrame.java`
- Represents a context frame tracking `frameId`, `parentFrameId`, `operationType`, `route`, and initialization time (`openedAt`).
- Currently tracked explicitly within `BifrostSession` to trace execution stack depth, separate from the primary `ExecutionJournal` raw data.

### JournalEntryType
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntryType.java`
- Provides an enum defining journal entry event names: `THOUGHT`, `PLAN_CREATED`, `PLAN_UPDATED`, `LINTER`, `TOOL_CALL`, `UNPLANNED_TOOL_EXECUTION`, `TOOL_RESULT`, `ERROR`.

## Code References
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java:31-37` - The internal `append` call used to log execution history with generically processed payloads.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:100-102` - The `logThought` method writing a `THOUGHT` entry type to the Journal.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:258-266` - The `getJournalSnapshot` which returns the complete unfiltered state of the event history.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionFrame.java:7-13` - Explicit representation of a route runtime frame scope.

## Architecture Documentation
The codebase uses `BifrostSession` to wrap context for localized LLM interactions and maintains state like the `ExecutionPlan`, last `LinterOutcome`, and stack trace depth (`ExecutionFrame`). A single linear `ExecutionJournal` tracks chronologically ordered `JournalEntry` records, capturing every event payload (serialized generically into Jackson `JsonNode` fields) across all active execution contexts globally for that session.

## Historical Context (from ai/thoughts/)
- `ai/thoughts/phases/phase5.md:22-24` - Mentions a Phase 5 goal to finalize the `ExecutionJournal` serialization so the developer can invoke `getSkillThoughts(skill_id)` to extract human-readable debug trajectories from the sub-agent traces without leaking private variables.
- `ai/thoughts/tickets/eng-022-safe-skill-thoughts-api-and-journal-views.md` - Explicit ticket establishing requirements for a safe abstraction preventing unfiltered raw journal blob dumps.

## Related Research
N/A

## Open Questions
- What abstraction design limits and filters journal queries safely?
- Will the filtering logic map via `ExecutionFrame` explicit correlation or rely on embedded fields within `JournalEntry` payloads?

## Follow-up Research [2026-03-21T18:44:31-07:00]

Based on the open questions above, the following implementation approaches are suggested:

### 1. JournalEntry Enhancements
Currently, journal entries lack native identifiers for tracing back to specific stack frames. To filter `JournalEntry` instances efficiently, the record should be augmented with explicit `frameId` and `route` fields. `BifrostSession` can populate these automatically by inspecting its internal stack (`frames.peek()`) whenever `logThought`, `logToolExecution`, etc., are called. This provides a strongly-typed structure making query lookups trivial without having to parse raw JSON payloads.

### 2. Diagnostics Abstraction Map
A clear demarcation is required between storage primitives (`ExecutionJournal`) and consumer views (a planned `DiagnosticsView` or `SkillThoughtTrace`). Instead of parsing raw JSON inside an API response, `BifrostSession` should expose a new method, potentially `getSkillThoughts(String route)` or `getDiagnostics(String frameId)`, that:
- Returns the newly created view model containing sanitized events.
- Fully exposes `THOUGHT` entries since they are natively developer-facing.
- Redacts payloads from `TOOL_CALL`, `TOOL_RESULT`, `ERROR`, and `LINTER` entries unless explicitly requested, exposing only the status or tool name to avoid leaking potential variables or proprietary structures.
