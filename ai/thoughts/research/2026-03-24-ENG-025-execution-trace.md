---
date: 2026-03-24T19:36:48-07:00
researcher: Codex
git_commit: 7da27583a5bce2bd0095d1d70ce81568d47ab6a3
branch: main
repository: bifrost
topic: "ENG-025 execution trace"
tags: [research, codebase, execution-trace, execution-journal, bifrost-session]
status: complete
last_updated: 2026-03-24
last_updated_by: Codex
---

# Research: ENG-025 execution trace

**Date**: 2026-03-24T19:36:48-07:00
**Researcher**: Codex
**Git Commit**: `7da27583a5bce2bd0095d1d70ce81568d47ab6a3`
**Branch**: `main`
**Repository**: `bifrost`

## Research Question

Research the current codebase state relevant to `ai/thoughts/tickets/eng-025-execution-trace.md`, especially the existing journaling, frame, planning, tool, chat client, and advisor flows that the ticket proposes to replace or extend.

## Summary

The current runtime record is centered on an in-memory `ExecutionJournal` stored directly on `BifrostSession`, not on a separate trace subsystem. `BifrostSession` owns the frame stack, the current plan, last linter and output-schema outcomes, usage snapshot state, and a mutable `ExecutionJournal` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:36`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:418`).

Journal writes are appended through session helper methods for thoughts, plans, tool calls, tool results, linter outcomes, output-schema outcomes, and errors. Each appended `JournalEntry` carries a timestamp, level, type, JSON payload, and optional `frameId` and `route` copied from the active frame (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java:36`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntry.java:11`).

`SkillThoughtTrace` is currently a derived view over journal entries for a specific route. `BifrostSession.getSkillThoughts(route)` delegates to `SkillThoughtMapper`, which filters entries by route and maps selected entry types into developer-readable `SkillThought` messages (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:143`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillThoughtMapper.java:10`).

The runtime seams called out in ENG-025 are already separate components today: frame and journal writes flow through `DefaultExecutionStateService`; planning through `DefaultPlanningService`; mission execution through `DefaultMissionExecutionEngine`; tool activity through `DefaultToolCallbackFactory`; advisor attachment through `SpringAiSkillChatClientFactory` and `DefaultSkillAdvisorResolver`; linter and output-schema validation through Spring AI `CallAdvisor` implementations (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:35`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:81`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:75`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:47`).

## Detailed Findings

### Session-Owned Observability State

- `BifrostSession` owns `sessionId`, `maxDepth`, a lock, a `Deque<ExecutionFrame>`, per-frame tool activity counts, `ExecutionJournal`, `ExecutionPlan`, last validation outcomes, usage snapshot, and authentication (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:28`).
- Session constructors initialize a fresh in-memory `ExecutionJournal` by default, and JSON construction also accepts a serialized journal and frame list (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:44`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:61`).
- All journal logging methods funnel into `appendJournalEntry(...)`, which inspects the active frame and forwards both `frameId` and `route` into the journal append call (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:418`).
- Session snapshots expose copies of frames and journal entries rather than the mutable backing collections (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:288`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:307`).

### Current Journal and Entry Model

- `ExecutionJournal` is a simple wrapper around `List<JournalEntry>` with append methods that convert arbitrary payload objects into Jackson `JsonNode` trees (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java:15`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java:36`).
- `JournalEntry` stores `timestamp`, `level`, `type`, `payload`, and nullable `frameId` and `route`, normalizing blank frame metadata to `null` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntry.java:11`).
- Current journal entry types are `THOUGHT`, `PLAN_CREATED`, `PLAN_UPDATED`, `LINTER`, `OUTPUT_SCHEMA`, `TOOL_CALL`, `UNPLANNED_TOOL_EXECUTION`, `TOOL_RESULT`, and `ERROR` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntryType.java:3`).
- The journal is append-only within the current process, but it is stored entirely in heap memory as a session field rather than streamed to external storage (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:36`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java:19`).

### Frame Model and Session Stack

- `ExecutionFrame` is a record with `frameId`, `parentFrameId`, `operationType`, `route`, `parameters`, and `openedAt` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionFrame.java:7`).
- `OperationType` currently includes `CAPABILITY`, `SKILL`, and `SUB_AGENT` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/OperationType.java:3`).
- `BifrostSession.pushFrame(...)` enforces `maxDepth`, and `popFrame()` also clears tool activity counts associated with that frame (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:248`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:261`).
- `DefaultExecutionStateService.openMissionFrame(...)` creates a new `ExecutionFrame` with a generated UUID, the current top frame as parent, `OperationType.SKILL`, route, parameters, and the current clock instant (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:35`).
- `closeMissionFrame(...)` verifies stack order by requiring the provided frame to match `session.peekFrame()` before popping (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:50`).

### SkillThoughtTrace Derivation

- `BifrostSession.getSkillThoughts(route)` is route-scoped and returns a `SkillThoughtTrace` built from the journal snapshot under the session lock (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:143`).
- `SkillThoughtMapper.toTrace(...)` filters journal entries strictly by `entry.route() == normalizedRoute` and maps only selected entry types into textual thoughts (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillThoughtMapper.java:10`).
- The mapper currently converts:
  - `THOUGHT` to text directly or a generic fallback
  - `TOOL_CALL` and `UNPLANNED_TOOL_EXECUTION` to tool-call summaries
  - `TOOL_RESULT` to completion summaries
  - `ERROR` to sanitized error summaries
  - `LINTER` to linter-attempt summaries
  (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillThoughtMapper.java:20`)
- `SkillThoughtTrace` itself is only `route` plus an immutable list of `SkillThought` items (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillThoughtTrace.java:5`).

### State Service as the Main Runtime Write Boundary

- `DefaultExecutionStateService` is the main abstraction bridging runtime behavior into session state and journal appends (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:19`).
- It owns frame open/close, plan store/restore, journal logging for plan and tool events, linter and output-schema outcome recording, and error logging (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:35`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:97`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:109`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:127`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:136`).
- Linter outcomes are both stored on session state and forwarded into `SessionUsageService`; output-schema outcomes are stored on session state and journaled (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:127`).

### Mission Execution Flow

- `DefaultMissionExecutionEngine.executeMission(...)` wraps execution in `SessionContextRunner.callWithSession(...)`, records mission start usage, optionally initializes a plan, builds an execution prompt from the current plan, and then issues a Spring AI `ChatClient` call (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:65`).
- When planning is enabled, the mission engine calls `planningService.initializePlan(...)` before the main execution call (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:80`).
- The execution prompt is derived from `ExecutionPlan` state and includes active, ready, and blocked task summaries (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:137`).
- Model usage is recorded after the response is received, but the engine itself does not currently append dedicated model-request or model-response journal records (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:97`).

### Planning Flow

- `DefaultPlanningService.initializePlan(...)` sends a planning prompt through `ChatClient`, extracts the response body, parses it as JSON or YAML, normalizes status values, converts it into `ExecutionPlan`, stores it through `ExecutionStateService`, and logs `PLAN_CREATED` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:81`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:124`).
- Planning responses are also recorded into session usage via `SessionUsageService.recordModelResponse(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:118`).
- Tool progress feeds back into plans through `markToolStarted`, `markToolCompleted`, and `markToolFailed`, each of which updates plan state and logs `PLAN_UPDATED` via `ExecutionStateService` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:129`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:148`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:165`).

### Tool Execution Flow

- `DefaultToolCallbackFactory` builds Spring AI `FunctionToolCallback` instances per visible capability (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:49`).
- `invokeCapability(...)` records usage, attempts to link the tool call to a plan task, logs either a linked or unplanned tool-call journal event, executes the capability through `CapabilityExecutionRouter`, updates the plan, logs a tool-result journal event, and records errors through `ExecutionStateService.logError(...)` when exceptions occur (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:75`).
- Tool event payloads are represented as `TaskExecutionEvent`, which carries `eventId`, `capabilityName`, optional `linkedTaskId`, a `details` map, and an optional note (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TaskExecutionEvent.java:9`).

### Chat Client Construction and Advisor Wiring

- `SpringAiSkillChatClientFactory.create(...)` resolves provider-specific chat options, resolves skill advisors, clones the base `ChatClient.Builder`, applies options and default advisors, and returns the built `ChatClient` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:47`).
- The factory contains provider-specific option adapters for OpenAI, Anthropic, Gemini, Ollama, and Taalas. Gemini configuration enables `includeThoughts(true)` when a thinking level is set (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:117`).
- `DefaultSkillAdvisorResolver.resolve(...)` attaches `OutputSchemaCallAdvisor` when an output schema exists and `LinterCallAdvisor` when a regex linter is configured (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:39`).
- Both advisor constructors receive callbacks that resolve the active `BifrostSession` from thread-local context and then record outcomes through `ExecutionStateService` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:50`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:65`).

### Advisor Behavior and Validation Outcomes

- `LinterCallAdvisor.adviseCall(...)` loops over downstream model calls until the regex matches or retries are exhausted, appends retry hints into the system prompt, records each `LinterOutcome`, and exposes the latest outcome under response context key `bifrost.linter.outcome` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java:16`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java:50`).
- `OutputSchemaCallAdvisor.adviseCall(...)` augments the prompt before the first call, validates each response, records retrying or passed outcomes, and throws `BifrostOutputSchemaValidationException` after exhaustion while also exposing the outcome context under `bifrost.output-schema.outcome` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java:21`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java:51`).
- These advisors record validation outcomes, but they do not currently emit a separate trace artifact abstraction; they feed state and journal writes through `ExecutionStateService` callbacks (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:50`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:65`).

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:36` - Session owns the canonical in-memory `ExecutionJournal`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:143` - `getSkillThoughts(route)` derives a `SkillThoughtTrace` from journal entries.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:418` - Session journal appends inherit `frameId` and `route` from the active frame.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionJournal.java:36` - Journal append converts payloads to `JsonNode` entries.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntry.java:11` - Current journal entry schema.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionFrame.java:7` - Current frame schema.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntryType.java:3` - Current journal taxonomy.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TaskExecutionEvent.java:9` - Current tool event payload model.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:35` - Frame lifecycle entry point.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:97` - Plan-created journaling.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:109` - Tool-call journaling.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:127` - Linter outcome recording and usage integration.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:65` - Mission execution loop.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:81` - Plan initialization from model output.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:129` - Tool-start plan updates.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:75` - Tool execution, journaling, and error flow.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:47` - Chat client construction and advisor attachment.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:39` - Advisor selection rules.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java:50` - Linter retry and recording loop.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java:51` - Output-schema retry and exception flow.

## Architecture Documentation

The current implementation uses `BifrostSession` as the central runtime state object for both execution control and observability. Execution frames are stack-based and session-local. Runtime services do not write directly to `ExecutionJournal` in many places; instead, they commonly call `ExecutionStateService`, which acts as the main state-transition and journaling boundary for frame lifecycle, plan lifecycle, tool activity, validation outcomes, and errors.

Planning and execution are separated into two model-interaction flows. `DefaultPlanningService` makes a planning call, parses it into an `ExecutionPlan`, stores it on the session, and journals the result. `DefaultMissionExecutionEngine` then optionally consumes that stored plan to build the execution system prompt before making the main mission call. Tool callbacks update both the plan and the journal as tools start, complete, or fail.

Developer-readable thought tracing is currently implemented as a projection layer on top of journal entries. `SkillThoughtTrace` does not capture raw model or advisor traffic directly. Instead, it filters and summarizes existing journal entries by route. Advisor-generated validation outcomes are also integrated indirectly by callbacks that record them on the current session and append journal entries through `ExecutionStateService`.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/phases/README.md:50` describes planning mode as transparently tracking progress in `ExecutionJournal`.
- `ai/thoughts/phases/README.md:63` describes the session model as holding telemetry and thinking logs in `ExecutionJournal`.
- `ai/thoughts/phases/phase2.md:36` describes `ExecutionJournal` as JSON-serializable session state intended as a future "Save Game" state.
- `ai/thoughts/phases/phase4.md:27` through `ai/thoughts/phases/phase4.md:32` describes linter support as Spring AI `CallAdvisor`-driven retries with observable outcomes.
- `ai/thoughts/phases/phase5.md:22` through `ai/thoughts/phases/phase5.md:24` describes `getSkillThoughts(skill_id)` as the developer-facing way to extract readable debug trajectories from sub-agent traces.

These historical notes line up with the current code structure: session-owned journal state, advisor-based validation loops, and a derived `getSkillThoughts(...)` view are all present in the live implementation.

## Related Research

- No prior documents were present under `ai/thoughts/research/` for this topic at the time of research.

## Open Questions

- No additional codebase questions were required to document the current state for ENG-025. Follow-up research could go deeper on persistence endpoints, sample/debug endpoints, or test coverage around journaling and session teardown if needed.
