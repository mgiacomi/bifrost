---
date: 2026-03-25T09:14:04-07:00
researcher: Codex
git_commit: 7da27583a5bce2bd0095d1d70ce81568d47ab6a3
branch: main
repository: bifrost
topic: "ENG-026 execution trace hardening"
tags: [research, codebase, execution-trace, hardening, architecture, reliability]
status: complete
last_updated: 2026-03-25
last_updated_by: Codex
---

# Research: ENG-026 execution trace hardening

**Date**: 2026-03-25T09:14:04-07:00
**Researcher**: Codex
**Git Commit**: `7da27583a5bce2bd0095d1d70ce81568d47ab6a3`
**Branch**: `main`
**Repository**: `bifrost`

## Research Question

Assess whether the repeated review findings against ENG-025 indicate a local implementation-quality problem, a deeper framework-architecture mismatch, or a trace-subsystem design that needs hardening before more feature work continues.

## Summary

The review set points to a subsystem-level hardening need, not to a broad framework failure. The framework already has good runtime seams for planning, mission execution, tool invocation, advisor attachment, and session coordination, but execution-trace semantics are still distributed across too many layers. That distribution makes correctness fragile even when the module test suite passes.

The strongest signal is not any single bug. It is the repeated pattern that the same trace contract is being partially defined in several places:

- runtime service methods in `DefaultExecutionStateService`
- session helper methods in `BifrostSession`
- mission and planning instrumentation in `DefaultMissionExecutionEngine` and `DefaultPlanningService`
- tool-path instrumentation in `DefaultToolCallbackFactory`
- advisor-local trace writes in `LinterCallAdvisor` and `OutputSchemaCallAdvisor`
- lifecycle, append barriers, chunking, and retention logic in `DefaultExecutionTraceHandle`
- readback semantics in `NdjsonExecutionTraceReader`
- developer-facing interpretation rules in `ExecutionJournalProjector`

This means the next stage should not be a loose cleanup checklist. It should be a dedicated hardening ticket with a clear contract, a central semantic boundary, and tests aimed at semantic drift.

## Detailed Findings

### 1. The Feature Seams Are Good; the Trace Ownership Boundary Is Not Yet Tight Enough

The runtime architecture already separates the major seams that matter for tracing:

- frame and state changes flow through [DefaultExecutionStateService.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java)
- planning runs through [DefaultPlanningService.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java)
- mission execution runs through [DefaultMissionExecutionEngine.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java)
- tool calls run through [DefaultToolCallbackFactory.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java)
- advisors are attached through [SpringAiSkillChatClientFactory.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java) and [DefaultSkillAdvisorResolver.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java)
- top-level finalization currently happens from [ExecutionCoordinator.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java)

That is encouraging. It means the framework likely can support the feature cleanly. The problem is that these seams still each participate in defining trace semantics instead of handing them to one canonical recorder contract.

### 2. Model Tracing Is Still Path-Specific Instead of Truly Central

Current model request and response records are emitted directly from:

- [DefaultPlanningService.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java#L121)
- [DefaultMissionExecutionEngine.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java#L103)

The chat-client creation boundary in [SpringAiSkillChatClientFactory.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java#L47) still does not appear to be the canonical source of model trace semantics. This aligns with review feedback that model tracing is complete only for selected flows and not for all chat-client usage.

### 3. Advisor Tracing Still Bypasses a Canonical Frame-Aware Writer

Both advisor implementations still append trace records via `BifrostSession.getCurrentSession().recordTrace(...)`:

- [LinterCallAdvisor.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java#L143)
- [OutputSchemaCallAdvisor.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java#L226)

That is better than bypassing the session entirely, but it still means advisor flows can define trace behavior locally. For a subsystem that needs strong guarantees, this is too permissive.

### 4. Session, State Service, and Trace Handle Still Share Semantics

Trace behavior is split across:

- [BifrostSession.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java#L140)
- [DefaultExecutionStateService.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java#L200)
- [DefaultExecutionTraceHandle.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java#L130)

`BifrostSession` stamps active-frame context and timestamp overrides, `DefaultExecutionStateService` decides record routing for some record types, and `DefaultExecutionTraceHandle` decides append, chunk, finalize, and retention semantics. This layering is workable, but only if the boundaries are explicit and narrow. The reviews suggest they are not narrow enough yet.

### 5. The Hardest Risks Are Lifecycle and Readback, Not Just Instrumentation

The most confidence-sensitive parts of the subsystem are:

- append-only guarantees and completion barriers in [DefaultExecutionTraceHandle.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/DefaultExecutionTraceHandle.java#L162)
- active read behavior during chunked payload writes in [NdjsonExecutionTraceReader.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReader.java#L27)
- developer-facing interpretation and deduplication in [ExecutionJournalProjector.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace/ExecutionJournalProjector.java#L23)
- finalization orchestration in [ExecutionCoordinator.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java#L94) and [BifrostSessionRunner.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSessionRunner.java#L70)

This is where a formal hardening pass pays off. These behaviors are subtle and easy to regress without contract-focused tests.

### 6. Property and Naming Contracts Are Part of the Subsystem Contract

The execution-trace persistence property is currently bound at the top-level namespace in [ExecutionTraceProperties.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/ExecutionTraceProperties.java#L9), and auto-configuration wires it into the session runner in [BifrostAutoConfiguration.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java#L105). Several reviews focused on configuration namespace and file naming expectations, which is a reminder that operational discoverability is part of the contract for this subsystem, not just an implementation detail.

### 7. Test Coverage Exists, but It Is Not Yet the Full Confidence Envelope

The starter module test suite currently passes in full:

- `./mvnw -pl bifrost-spring-boot-starter test`
- 222 tests passed on 2026-03-25

Relevant tests already exist for:

- state-service trace behavior in [ExecutionStateServiceTest.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java)
- planning trace behavior in [PlanningServiceTest.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java)
- trace handle behavior in [ExecutionTraceHandleTest.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceHandleTest.java)
- live chunk-read behavior in [NdjsonExecutionTraceReaderTest.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/NdjsonExecutionTraceReaderTest.java)
- finalized JSON round-trip behavior in [BifrostSessionJsonTest.java](/C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/BifrostSessionJsonTest.java)

That said, the reviews show that passing tests are not yet enough to guarantee the intended trace contract. The remaining gap is not “no tests.” It is “not enough contract-level tests that compare semantics across flows and lifecycle states.”

## Conclusion

The right next move is a dedicated hardening ticket, not a broad framework rethink and not an unstructured sequence of local fixes.

The framework architecture appears capable of supporting execution tracing well. The trace subsystem itself needs one more round of intentional consolidation:

- define the contract explicitly
- centralize semantic ownership
- narrow the number of approved writers
- test the lifecycle and cross-flow invariants directly

That is the smallest step that meaningfully improves confidence.

## Related Research

- `ai/thoughts/research/2026-03-24-ENG-025-execution-trace.md`

## Related Tickets

- `ai/thoughts/tickets/eng-025-execution-trace.md`
- `ai/thoughts/tickets/eng-026-execution-trace-hardening.md`
