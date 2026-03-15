# Ticket: eng-007-execution-coordinator.md
## Issue: Implement ExecutionCoordinator and Internal MCP-like Routing

### Context
This is the core loop engine. The `ExecutionCoordinator` ties the Session, the Registry, the Tools, and the AI into a cohesive cognitive execution loop.

### Requirements
1. **`ExecutionCoordinator` component:**
   - Create the central service bean. It depends on `CapabilityRegistry`, `BifrostSession`, the Transformers, and `ChatClient` models.
2. **Internal Routing Loop (`callSkill` loop):**
   - The coordinator formats the system prompt, attaches available tools (mapping them via Spring AI tools API directly to the generated `Function`s from the `CapabilityRegistry`).
   - Loop triggers `ChatClient.call()`.
3. **Planning Mode (Cognitive Anchor):**
   - If `planning_mode` is active (global default), force the LLM to output a "Flight Plan" (Task List) as its first response step.
   - Record this list natively into the `BifrostSession`.
   - On subsequent tool calls within this mission, keep updating the status natively in the `ExecutionJournal`.
4. **`ref://` Pointer Resolution:**
   - If an input payload strictly contains a `ref://...` signature, intercept it.
   - Utilize Spring's `ResourceLoader` to parse the underlying bytes/text into memory *before* feeding it to the `@SkillMethod`, providing memory offloading.

### Acceptance Criteria
- End-to-end integration test initializes a dummy session, sends a "hello world" objective. 
- The coordinator pushes the planning step if enabled, successfully loops the `ChatClient` using the mock functions, resolves at least one `ref://` string injection correctly, and unwinds without violating `MAX_DEPTH`.
