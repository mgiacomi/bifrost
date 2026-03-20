# Ticket: eng-007-execution-coordinator.md
## Issue: Implement ExecutionCoordinator and Internal MCP-like Routing

### Context
This is the core loop engine. The `ExecutionCoordinator` ties the Session, the Registry, the Tools, and the AI into a cohesive cognitive execution loop.

### Requirements
1. **`ExecutionCoordinator` component:**
   - Create the central service bean. It depends on `CapabilityRegistry`, `BifrostSession`, the Transformers, validated YAML skill execution metadata, the shared `SkillChatClientFactory`, and `ChatClient` models.
2. **Internal Routing Loop (`callSkill` loop):**
   - Consume the validated `EffectiveSkillExecutionConfiguration` produced during ENG-005 rather than re-resolving model or thinking settings at execution time.
   - The coordinator formats the system prompt, attaches available tools (mapping them via Spring AI tools API directly to the generated `Function`s from the `CapabilityRegistry`).
   - Loop triggers `ChatClient.call()`.
3. **Planning Mode (Cognitive Anchor):**
   - Planning mode is enabled by default for the coordinator, with an optional per-skill YAML `planning_mode` override.
   - If `planning_mode` is active for the current skill, force the LLM to output a "Flight Plan" (Task List) as its first response step.
   - Record this list natively into the `BifrostSession`.
   - On subsequent tool calls within this mission, keep updating the status natively in the `ExecutionJournal`.
4. **`ref://` Pointer Resolution:**
   - If an input payload contains exact scalar `ref://...` values, intercept them before deterministic invocation.
   - Apply this resolution recursively across structured argument payloads so nested maps, lists, and arrays can carry opaque refs.
   - Utilize Spring's `ResourceLoader` to parse the underlying bytes/text into memory *before* feeding it to the `@SkillMethod`, providing memory offloading.
5. **Skill Discovery Surface:**
   - The LLM-visible tool surface for the coordinator is YAML capability based.
   - Raw discovered `@SkillMethod` implementation targets remain internal implementation details and are not directly exposed to the LLM tool list.
   - Skill visibility is constrained by RBAC plus YAML-declared `allowed_skills` relationships for the MVP.
   - Global skill exposure is out of scope for this iteration.

### Clarified Design Decisions
1. **Planning State Representation**
   - `BifrostSession` should hold the active plan as strongly typed Java domain objects rather than a raw JSON blob.
   - The recommended shape is an execution-plan aggregate with ordered task items and explicit task status values.
   - Journal entries should record plan snapshots or equivalent structured plan updates so the runtime state and audit trail remain separate concerns.
2. **`ref://` Semantics**
   - `ref://` is an opaque, session-local pointer into the session's virtual file system rather than a direct filesystem path.
   - Resolution should still use strict matching at the leaf level: only exact scalar values equal to `ref://...` are dereferenced, while non-matching strings remain untouched.
   - Argument resolution may walk nested maps, lists, and arrays so those exact scalar leaf values can be dereferenced inside structured payloads.
   - `readData` and `writeData` are the intended capability surface for creating and consuming these refs.
   - The pointer contract must remain storage-implementation agnostic so future VFS adapters such as S3 or Azure Blob can interpret refs without leaking backend-specific paths into skill code.
3. **Skill Discovery Rules**
   - For the MVP, visibility between YAML skills is controlled by explicit `allowed_skills` declarations on the calling skill.
   - Tag-based discovery is deferred until after developer feedback validates the simpler model.
   - RBAC filtering should be applied before tools are exposed to the LLM, with execution-time enforcement remaining in place as a second boundary.

### Acceptance Criteria
- End-to-end integration test initializes a dummy session, sends a "hello world" objective. 
- The coordinator pushes the planning step when enabled for the current skill, successfully loops the `ChatClient` using the mock functions, resolves at least one `ref://` string injection correctly, and unwinds without violating `MAX_DEPTH`.
- The planning step is stored as structured session state and journaled as structured updates.
- `ref://` resolution is validated against a session-scoped VFS abstraction rather than a hard-coded filesystem path, including nested structured arguments with exact scalar `ref://...` leaves.
- The coordinator exposes only YAML-visible skills after RBAC and `allowed_skills` filtering; raw `@SkillMethod` implementation targets are not presented directly to the LLM.
