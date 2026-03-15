# Ticket: eng-004-execution-journal.md
## Issue: Implement ExecutionJournal Mechanism

### Context
For telemetry and persistence (saving game state), the reasoning engine must record traces to an `ExecutionJournal`.

### Requirements
1. **`ExecutionJournal` & `JournalEntry` Models:**
   - Create `JournalEntry` records containing `timestamp`, `level`, `type` (e.g., THOUGHT, TOOL_CALL, TOOL_RESULT, ERROR), and `payload` (JSON node/String).
   - Create `ExecutionJournal` to manage a list of `JournalEntry` objects.
2. **Session Integration:**
   - Attach `ExecutionJournal` directly to `BifrostSession`.
3. **JSON Serialization:**
   - Use Jackson annotations to ensure `ExecutionJournal` state cleanly serializes into JSON. This will be later used to persist mission progress to JDBC/Redis.
4. **Task/Planning log support:**
   - Provide easy append methods (`logThought(..)`, `logToolExecution(..)`, `logError(..)`) so the coordinator easily pushes to it.

### Acceptance Criteria
- `ExecutionJournal` accepts sequential records.
- Complete snapshot serialization to JSON and parsing back into valid objects functions seamlessly (proved by a JUnit test).
- Does not block or perform poorly within a Virtual Thread.
