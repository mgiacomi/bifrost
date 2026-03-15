# Ticket: eng-004-execution-journal.md
## Issue: Implement ExecutionJournal Mechanism

### Context
For telemetry and persistence (saving game state), the reasoning engine must record traces to an `ExecutionJournal`.

### Requirements
1. **`ExecutionJournal` & `JournalEntry` Models:**
   - Create `JournalEntry` records containing `timestamp`, `level`, `type` (e.g., THOUGHT, TOOL_CALL, TOOL_RESULT, ERROR), and `payload`.
   - Accept ergonomic `Object` payloads at the logging API boundary, but eagerly convert them into a JSON-stable representation such as `JsonNode` when the entry is created so the journal remains a literal snapshot of mission state instead of holding live POJO references.
   - Create `ExecutionJournal` to manage a list of `JournalEntry` objects.
2. **Session Integration:**
   - Attach `ExecutionJournal` directly to `BifrostSession`.
3. **JSON Serialization:**
   - Use Jackson annotations to ensure `ExecutionJournal` state cleanly serializes into JSON. This will be later used to persist mission progress to JDBC/Redis.
   - Treat runtime-only coordination objects such as `ReentrantLock` as transient lifecycle state. Ignore them during serialization and rehydrate fresh instances when a session is deserialized.
4. **Task/Planning log support:**
   - Provide easy append methods (`logThought(..)`, `logToolExecution(..)`, `logError(..)`) so the coordinator easily pushes to it.

### Acceptance Criteria
- `ExecutionJournal` accepts sequential records.
- Complete snapshot serialization to JSON and parsing back into valid objects functions seamlessly (proved by a JUnit test).
- Does not block or perform poorly within a Virtual Thread.
- Journal ordering remains consistent with session state by sharing the existing in-memory session lock for MVP writes.
