# Phase 6 - Plan-Step Execution Loop

Date: 2026-03-29

## Goal
Evolve Bifrost from a single-shot plan-guided mission call into a plan-step execution loop where the runtime repeatedly re-anchors the model on the latest plan state, executes the next valid action, updates the stored plan, and continues until the mission is complete, intentionally aborted, or fails under a governed policy.

## Why This Phase Exists
The current planning architecture gives the model a useful initial cognitive anchor, but the anchor is only injected once per top-level mission model call. The runtime continues to track and mutate plan state through `PLAN_CREATED` and `PLAN_UPDATED` records, but the model does not automatically see those later transitions unless a new model call is made.

This creates a mismatch:
- The runtime knows which tasks are now ready.
- The trace clearly shows task transitions.
- The model may still behave as though it is operating on an earlier snapshot.

In recent traces this showed up as:
- The plan being created successfully.
- A ready task being selected and executed.
- Dependent tasks becoming newly unblocked after the first task completed.
- The model returning a final answer without seeing the refreshed ready-task list.

Phase 6 is about closing that alignment gap.

## Decisions Finalized
The following decisions are now locked for the Phase 6 implementation.

### Execution Mode
- `planning_mode: true` means the skill executes through the plan-step loop.
- `planning_mode: false` keeps the non-planned one-shot execution path.
- A separate `execution_mode` setting is not needed and should be removed.
- Because `planning_mode: true` selects the Phase 6 execution model directly, it also selects the stricter Phase 6 planning contract.
- The library should not preserve an internal fallback where a planned skill can silently execute on the one-shot path because step-loop wiring is missing.

### Step Contract
- V1 step actions are limited to exactly two choices:
  - `CALL_TOOL`
  - `FINAL_RESPONSE`
- Each step may execute at most one tool call.
- The runtime, not the model, remains responsible for validating that the chosen task is ready and the chosen tool is allowed.
- The step-action envelope should use protocol-specific field names that are unlikely to collide with business payload schemas.
- The preferred V1 envelope uses `stepAction` rather than a generic `action` field.
- Free-form per-step `reasoning` should not be required in the action contract because it collides too easily with normal output-schema fields and adds more confusion than control value.

### Plan Contract
- `planning_mode: true` opts the skill into the stricter Phase 6 plan contract.
- That contract applies at plan creation time, not only after execution begins.
- Every task must have a unique `taskId`.
- Every non-auto-completable task must declare a `capabilityName`.
- `autoCompletable` tasks are not supported in Phase 6.
- Plans that violate this contract should be rejected rather than accepted and deferred to later runtime handling.

### Completion Policy
- Completion is strict in Phase 6.
- A `FINAL_RESPONSE` is only valid when all required plan tasks are completed.
- When all required plan tasks are completed, the runtime should treat the mission as being in a terminal completion state rather than presenting another open-ended tool-selection turn.
- The Phase 6 loop should either force a `FINAL_RESPONSE` path or otherwise constrain the next decision so `CALL_TOOL` is no longer a valid option once the plan is fully complete.
- Deadlock, invalid plans, repeated invalid actions, or step-limit exhaustion must produce explicit terminal failure states rather than forcing a best-effort final answer.
- A separate configurable completion policy is not needed in this phase and should not be introduced yet.

### Prompt Context
- The step prompt should carry only bounded, runtime-owned context:
  - mission objective
  - ordered task summary
  - active task if present
  - ready tasks
  - pending tasks waiting on dependencies
  - blocked or failed tasks
  - short runtime execution summary
  - a bounded digest of the most recent tool result
- The prompt should not replay full history or reinject the full raw plan document after initialization.

### Validation and Retry Policy
- JSON shape enforcement and runtime semantic validation are separate concerns.
- Planning-time plan generation is validated by the planning contract and runtime plan parsing, not by final-response linter or output-schema advisors.
- Final-response validators apply to mission output, not to the planning call that creates the plan.
- Runtime validation must reject:
  - unknown tasks
  - tasks that are not currently ready
  - disallowed or mismatched tools
  - tool invocations that violate an already-declared tool input contract when the runtime already has a concrete non-generic schema to enforce
  - tool invocations that are obviously incompatible with the current task contract when the runtime can determine that incompatibility from task/tool metadata or missing required arguments
  - `FINAL_RESPONSE` while any required task remains incomplete
- Invalid actions may receive a bounded correction retry.
- Repeated invalid actions should fail the mission rather than silently degrading policy.
- Phase 6 should prefer reusing existing tool contracts over introducing a new YAML-skill-specific input authoring model in this phase.

### Trace Semantics
- The trace must clearly show:
  - step start
  - model proposal
  - validation result
  - step completion
  - explicit terminal failure when the loop cannot continue under policy
- Trace output should make it obvious whether a failure came from model behavior, validation rejection, tool execution failure, or plan deadlock.

### Task Binding
- When the model selects a `taskId`, the runtime must preserve that exact task binding through execution.
- Deterministic binding requires `taskId` values to be unique within a plan.
- The step loop must not re-link the task indirectly by capability name once validation has already accepted a specific task.
- Deterministic task binding is part of the core value of this phase and is required for trustworthy traces.

## Primary Outcomes
- The model receives an updated plan summary on every execution step, not just once at mission start.
- The runtime remains the authoritative owner of task readiness, task transitions, and mission completion policy.
- Small models can operate against shorter, clearer, task-focused prompts instead of one long mission call with stale state.
- Trace records become easier to interpret because each model decision corresponds to a specific plan state and resulting action.
- The framework gains a path toward more deterministic, policy-driven orchestration without abandoning LLM flexibility.

## Recommendation
The recommended direction is to make `planning_mode` the execution-mode switch directly:
- `planning_mode: true` executes through the plan-step loop.
- `planning_mode: false` keeps the existing non-planned one-shot path.
- No separate feature flag or additional execution-mode setting should be introduced for Phase 6.

The preferred shape is:
1. Create the plan once up front using the existing planning flow.
2. Enter a loop that rebuilds the execution prompt from the latest stored plan.
3. Ask the model for exactly one next action.
4. Validate that action against runtime state.
5. Execute the action if valid.
6. Update the plan and trace.
7. Repeat until exit criteria are met.

This recommendation is intentionally direct rather than hybrid:
- Keep the runtime authoritative.
- Keep the plan as the stable execution contract.
- Avoid asking the model to fully re-plan each turn.
- Avoid carrying unbounded history forward.
- Limit each step to one clear decision.

## Why This Recommendation
### Reliability
A step loop directly addresses the observed failure mode where the model never sees that formerly blocked tasks have become ready.

### Better Fit for Small Models
Short, narrow prompts that ask for one valid next action are likely to perform better on smaller local models than a long, multi-stage mission prompt where the model must remember a changing plan while also deciding whether to call tools.

### Architectural Coherence
Bifrost already has most of the core pieces required:
- `PlanningService` initializes the plan.
- `ExecutionStateService` stores and updates it.
- Tool/task linking already exists.
- Plan events are already observable in trace output.

The major change is orchestration flow, not the core concept of planning.

## Tradeoffs
## Benefits
- **State alignment:** The model sees current ready tasks on every step.
- **Reduced task skipping:** Newly unblocked work becomes visible to the model immediately on the next turn.
- **Trace clarity:** Each step can be tied to a single model call and a single chosen action.
- **Policy control:** The runtime can reject invalid actions before they become side effects.
- **Prompt quality for small models:** Prompts can be shorter, clearer, and more constrained.

## Costs
- **Higher latency:** Every step requires another model round-trip.
- **More orchestration complexity:** The engine must validate actions, manage loop exits, and handle invalid model outputs gracefully.
- **Context continuity risk:** If prompts are too narrow, the model may lose useful prior reasoning.
- **Implementation surface area:** Trace semantics, advisor behavior, task-linking expectations, and test coverage will all need to evolve.

## Key Design Principle
The runtime must remain the source of truth.

The model should not be trusted to determine:
- whether a task is actually ready,
- whether a tool call is valid for the current task,
- whether the mission may complete early,
- whether the plan structure should be mutated,
- whether a blocked task can be executed.

The model may propose the next action, but the runtime must validate and enforce it.

## Scope
- Introduce a step-oriented mission execution loop for planned skills.
- Define a bounded action contract for the model.
- Enforce the stricter Phase 6 plan contract for skills using `planning_mode: true`.
- Rebuild execution prompts from live plan state each loop iteration.
- Preserve high-quality trace output for every step transition.
- Define completion, failure, retry, and early-stop policies.
- Preserve the existing non-planned one-shot path for skills that do not enable planning.

## Explicitly Out of Scope for the First Iteration
- Full autonomous re-planning on every step.
- Self-modifying plans without explicit runtime approval.
- Multi-agent or parallel plan execution.
- Full conversation replay of every previous prompt and tool result.
- Replacing existing advisors wholesale before the loop behavior is proven.

## Current-State Summary
Today the top-level mission engine roughly works like this:
1. Create a plan if planning is enabled.
2. Build one execution prompt from the current plan snapshot.
3. Make one mission model call.
4. Allow that model call to invoke tools.
5. Let runtime bookkeeping update the plan while the model call is still conceptually in flight.
6. Accept the final model answer.

This means the model may operate on stale plan context even though the runtime state is changing correctly underneath it.

## Target-State Summary
The future looped flow should work more like this:
1. Initialize the plan.
2. Read the latest plan from `ExecutionStateService`.
3. Build a step prompt from the current plan snapshot plus bounded recent execution context.
4. Ask the model for one next action.
5. Validate the proposed action against current runtime state.
6. Execute the action or reject/correct it.
7. Log all step artifacts to trace.
8. Re-read the plan.
9. Continue until the mission reaches a governed terminal state.

## Proposed Runtime Model
### 1. Step-Oriented Model Calls
Instead of one long mission-model call, Bifrost should open a model frame per execution step. Each step should correspond to a specific decision point.

Benefits:
- Better trace readability.
- Easier debugging.
- Cleaner coupling between prompt, plan state, and chosen action.

### 2. Strict Next-Action Contract
The model should return a structured next-step decision instead of free-form mixed reasoning plus opportunistic tool calls.

A first-pass action contract might include actions such as:
- `CALL_TOOL`
- `FINAL_RESPONSE`
- `COMPLETE_TASK_WITHOUT_TOOL`
- `FAIL_TASK`
- `REQUEST_CLARIFICATION`
- `NO_OP` or `WAIT` only if there is a very clear policy for it

Each action should have explicit required fields. Example categories to think through:
- target `taskId`
- target `capabilityName`
- arguments or references
- final response payload
- reason or note

The exact schema should be kept intentionally small.

For Phase 6 V1, the preferred minimal envelope is:

```json
{
  "stepAction": "CALL_TOOL",
  "taskId": "<taskId>",
  "toolName": "<toolName>",
  "toolArguments": { }
}
```

or

```json
{
  "stepAction": "FINAL_RESPONSE",
  "finalResponse": { }
}
```

Notes:
- `finalResponse` should carry the mission payload itself, not a second string-encoded JSON document.
- The control envelope should avoid common payload names like `action`, `reasoning`, `status`, `message`, or `details` wherever possible.

### 3. Runtime Validation Layer
Before executing a model-proposed action, validate:
- Does the target task exist?
- Is that task currently ready?
- Is the selected capability allowed for that task?
- Is the requested action compatible with current mission state?
- If the selected tool already exposes a concrete non-generic input schema, do the provided arguments satisfy its required top-level fields?
- Is the proposed tool invocation semantically plausible for the task, rather than merely syntactically shaped?
- Is the proposed final response allowed while tasks remain unfinished?

If validation fails, the runtime should not blindly comply. It should either:
- retry with a corrected prompt,
- emit a structured invalid-action event and ask again,
- or fail the mission under policy if the model repeatedly refuses to comply.

### 4. Bounded Context Carry-Forward
A naive step loop can create context loss if each step is too isolated. A naive full-history carry-forward can recreate the current drift problem.

The first design should therefore use bounded carry-forward, such as:
- mission objective,
- current plan summary,
- active task,
- ready tasks,
- blocked tasks,
- the most recent relevant tool outputs,
- a short running execution summary written by the runtime.

The runtime summary should be concise and structured. It should not become a second hidden plan.

### 5. Controlled Completion Policy
The runtime must define whether a mission may end while tasks remain.

Options to evaluate:
- **Strict completion:** final response is rejected if required tasks remain incomplete.
- **Soft completion:** model may finish early if it provides a rationale and runtime policy allows it.
- **Capability-specific completion policy:** certain skills may allow early finalization, others may require exhausting all mandatory tasks.

This must be explicit before implementation begins.

## Detailed Tasks
### 1. Define the Loop Contract
- Decide the exact structured action schema the model must produce per step.
- Decide whether that schema should be enforced by `OutputSchemaCallAdvisor`, a separate step-specific validator, or both.
- Define which action types are valid in v1.
- Define how invalid actions are surfaced in trace and telemetry.

### 2. Introduce a Step-Oriented Execution Engine Path
- Route planned skills directly into the plan-step orchestration path.
- Keep the existing one-shot path only for skills that are not using planning.
- Break mission execution into repeated step calls rather than a single long mission-model call.

### 3. Build a Step Prompt Generator
- Create a dedicated prompt builder for loop iterations.
- Include objective, plan status, active task, ready tasks, blocked tasks, and recent execution summary.
- Keep the prompt concise enough for small local models.
- Avoid reinserting the entire raw plan JSON unless testing proves it necessary.

### 4. Add Runtime Action Validation
- Validate step responses against current plan state before side effects occur.
- Reject blocked-task execution.
- Reject unknown or mismatched capability names.
- Decide how many correction attempts to allow before mission failure.

### 5. Define Task Transition Semantics
- Clarify when a task moves to `IN_PROGRESS`.
- Clarify whether a task can be completed without a tool call.
- Clarify whether multiple ready tasks may be executed in one step or whether v1 is strictly one-task-at-a-time.
- Clarify how retries interact with task status.

### 6. Define Early Completion and Dead-End Policies
- What happens if the model returns a final answer while tasks remain ready?
- What happens if no tasks are ready but the plan is not terminal?
- What happens if all remaining tasks are blocked forever because of a malformed plan?
- What happens if a task repeatedly fails?

### 7. Evolve Trace Semantics
- Add clear records for step start, step proposal, step validation, step execution, and step completion.
- Preserve plan events so the CLI trace tree remains understandable.
- Ensure developers can distinguish invalid model choices from runtime execution failures.

### 8. Revisit Advisor Integration
- Decide whether existing linter and output-schema advisors should continue to operate per step, only on final response actions, or differently depending on action type.
- Planning calls should not reuse final-response linter or output-schema validation, because plan creation is governed by the Phase 6 planning contract instead.
- Avoid advisor behavior that accidentally triggers repeated tool execution.
- Consider whether action-schema validation should happen before final answer validation.

### 9. Add Focused Test Coverage
- Unit-test prompt generation from changing plan states.
- Unit-test invalid action rejection.
- Unit-test correct advancement from `PENDING` to `IN_PROGRESS` to `COMPLETED`.
- Add integration tests for task unblocking after prior task completion.
- Add regression tests proving the model cannot silently skip newly unblocked tasks under strict policy.
- Add regression tests for the terminal-completion path, including the case where all tasks are completed, no ready tasks remain, and the model still proposes `CALL_TOOL`.
- Add regression tests proving existing non-generic tool schemas can reject missing required arguments without introducing new skill-manifest authoring requirements.
- Add regression tests showing the loop either recovers from or explicitly rejects semantically empty tool calls that do not meaningfully satisfy the active task.

### 10. Roll Out Safely
- Guard the new path behind configuration.
- Compare latency, completion quality, tool accuracy, and trace clarity against the current path.
- Test first on small-model skills that already show drift or under-execution.

## Suggested First Implementation Slice
A small but meaningful prototype should aim for the following:
- Planning still happens once using the current planner.
- The loop handles exactly one ready task per step.
- The model may choose between `CALL_TOOL` and `FINAL_RESPONSE` only.
- The runtime rejects `FINAL_RESPONSE` while any mandatory ready task remains.
- The runtime rebuilds the prompt after each tool result.
- Trace output clearly marks each step.

This slice is intentionally narrow. It is enough to prove whether the plan-step approach materially improves alignment before adding advanced actions.

## Alternative Paths Considered
### A. Keep the Current One-Shot Model and Only Improve the Prompt
This is the lowest-cost option, but it does not solve the core issue that plan state changes after the prompt is created.

### B. Re-inject Plan Updates into One Ongoing Conversation
This could preserve more continuity, but it risks prompt growth, ambiguous turn structure, and continued drift for small models.

### C. Full Dynamic Re-Planning
This is powerful but too risky for the first pass. It introduces instability in both execution and observability before the more basic step-alignment problem is solved.

The plan-step loop is the strongest middle ground.

## Open Questions to Resolve Before Implementation
### Product and UX Questions
- Is the plan advisory or mandatory?
- Are all tasks required to complete before a final answer is accepted?
- Should the framework allow capability-specific completion policies?
- How much latency increase is acceptable in the sample app and in production usage?

### Prompting Questions
- Should the step prompt include full prior tool outputs or only a summarized digest?
- How much previous step history should be carried forward?
- Should the model ever see the raw plan JSON after initialization, or only a normalized summary?
- Should blocked tasks be shown in detail or summarized compactly?

### Runtime Semantics Questions
- What is the exact definition of a ready task?
- Can the model complete a task without calling a tool?
- Can one step execute more than one tool, or should that be prohibited entirely in v1?
- How should retries affect task status and trace semantics?
- What happens if the model repeatedly proposes invalid actions for the same step?

### Validation Questions
- Should step-action validation use the existing output-schema machinery or a dedicated validator?
- Should invalid actions be repaired through a retry hint or immediately fail the step?
- How many invalid-action retries are acceptable before mission failure?

### Trace and Observability Questions
- Do we need a new trace record type for step proposals and validation failures?
- How should the CLI present repeated step loops so the tree remains readable?
- What telemetry should compare one-shot vs plan-step execution?

### Rollout Questions
- Should plan-step mode remain tied directly to `planning_mode`, or is there a future need for a broader global override?
- Which existing sample skills are best for proving the value of the loop?
- What metrics define success strongly enough to justify broader rollout?

## Metrics Worth Comparing During a Prototype
- Mission completion rate.
- Rate of skipped ready tasks.
- Number of invalid tool selections.
- Number of retries per task.
- Time to first useful action.
- End-to-end mission latency.
- Total model calls per mission.
- Token usage and prompt size by step.
- Final answer quality for small local models.

## Deliverables
- Plan-step execution path for skills with `planning_mode: true`.
- Stricter plan validation for skills with `planning_mode: true`, including required task capability bindings and rejection of `autoCompletable` tasks.
- Step prompt builder and structured action contract.
- Runtime validator for proposed actions.
- Updated trace semantics for step-level visibility.
- Regression tests proving newly unblocked tasks become visible and actionable on subsequent turns.
- Documentation describing the Phase 6 contract and failure semantics.

## Exit Criteria
- A planned mission can execute across multiple model turns with the runtime rebuilding prompt context from the latest stored plan each turn.
- Skills using `planning_mode: true` reject plans that violate the Phase 6 task contract.
- Newly unblocked tasks become visible to the model on the next loop iteration.
- The runtime rejects invalid or blocked task actions before side effects occur.
- Trace output clearly shows the loop sequence and plan transitions.
- The implementation demonstrates a measurable reduction in task skipping compared with the prior one-shot planned execution behavior.
- Planned execution remains clean and deterministic without overlapping execution-mode switches.

## Suggested Next Discussion
Before coding begins, the next planning discussion should explicitly decide:
1. Whether plan completion is strict or advisory.
2. The minimum viable action schema for one loop step.
3. Whether v1 allows only one tool call per step.
4. How much recent execution context is carried forward.
5. Which trace records need to be added or revised.
6. What success metrics would justify continuing after a prototype.

## Resolution of the Suggested Discussion
The prototype decisions are now resolved as follows:
1. Plan completion is strict.
2. The minimum viable action schema is `CALL_TOOL` and `FINAL_RESPONSE`.
3. V1 allows only one tool call per step.
4. Recent context is bounded to runtime-authored summaries and the latest relevant tool-result digest.
5. Step-level trace records are required, along with explicit terminal-failure visibility.
6. The prototype succeeds if it materially reduces skipped ready tasks while preserving understandable traces and acceptable latency for small-model skills.

## Implementation Note
- Phase 6 should remain explicit in both production code and tests: if a skill or integration scenario is intended to exercise the plan-step loop, its YAML fixture should declare `planning_mode: true`.
- Recursive or nested planned-skill scenarios should enable `planning_mode: true` on each participating skill fixture, not only on the root skill.
