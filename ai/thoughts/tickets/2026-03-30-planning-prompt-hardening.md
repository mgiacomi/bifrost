# Ticket - Strengthen Plan Generation for Small Models

Date: 2026-03-30

## Summary
Recent Phase 6 manual testing shows the step loop is materially more robust than it was at the start of implementation, but planning quality remains weak for smaller models. The most visible failure pattern is not random invalid JSON or protocol confusion anymore. It is the planner producing shallow, repetitive plans that overuse a single tool, underuse other allowed tools, and create semantically weak tasks that technically execute but do not meaningfully advance the mission.

This ticket proposes a planning-focused engineering pass to improve plan quality without overfitting to any one model and without introducing a new YAML-skill input-contract system in this phase.

## Problem Statement
In repeated `duplicateInvoiceChecker` traces, the planner tends to:
- create multiple sequential tasks bound to `invoiceParser`
- omit `expenseLookup` even though it is allowed and mission-relevant
- create task descriptions whose intent does not match the selected tool well
- produce plans that technically satisfy the current structural contract but are too weak to drive trustworthy execution

This leads to a new class of failure:
- the run may now complete successfully under Phase 6 runtime policy
- but the resulting mission answer is semantically weak because the plan never required the right evidence-gathering steps

The framework needs to do more to help smaller models produce better plans before execution begins.

## Goals
- Improve planning quality for smaller models using clearer planning-time guidance.
- Preserve model-agnostic framework behavior rather than tuning for one provider or one model family.
- Catch obviously weak plans before the step loop starts.
- Encourage better task-tool alignment when multiple allowed tools exist.
- Keep the solution within planning and plan validation scope rather than introducing a broad new skill authoring system.

## Non-Goals
- Do not introduce a new universal `input_schema` or YAML-specific input contract in this ticket.
- Do not require every skill author to annotate tasks manually.
- Do not introduce dynamic replanning on every step.
- Do not build a domain-specific rule set for `duplicateInvoiceChecker` alone.
- Do not block all repeated use of a single tool. Repetition can be valid in some missions.

## Observed Failure Patterns
The recent traces suggest a stable pattern:

1. The planner decomposes the mission into generic-sounding subtasks rather than capability-grounded subtasks.
2. The planner reuses one familiar tool for most or all tasks, even when another allowed tool is clearly relevant.
3. The planner creates end-of-plan tasks such as "generate report" or "confirm result" that are still bound to the same extraction tool.
4. The step loop then executes those tasks faithfully, including semantically empty tool calls like `{}` when the plan itself gave the model no better path.
5. The final response may satisfy `output_schema` while still being under-evidenced.

## Hypothesis
Small models can likely produce better plans if the planning prompt is more explicit about:
- tool differentiation
- task-tool alignment
- avoiding redundant tasks
- what mission evidence is required before the mission is complete

Prompting alone will not fully solve planning quality for all future skills, but there is still meaningful leverage left at the planning layer. That leverage should be captured before concluding that stronger planning models are required.

## Proposed Work
### 1. Strengthen the Planning Prompt
Revise the planning prompt so it is much more explicit about what makes a good plan.

The prompt should strongly communicate:
- each task must have a distinct purpose
- each task should be assigned to the tool that best matches that purpose
- if multiple allowed tools exist, the planner should consider whether more than one tool is necessary for mission success
- repetitive reuse of the same tool requires justification in the task intent
- report-generation or conclusion tasks should not be assigned to a tool that only performs extraction or lookup unless that mapping is truly correct
- the mission should not be considered complete until the plan gathers enough evidence to support the required final output

The prompt should also ask the model to think in terms of mission evidence, not just task wording. For example:
- what information must be extracted?
- what information must be looked up or compared?
- which tool is best suited for each?

### 2. Improve Tool Presentation During Planning
Review how allowed tools are presented to the planner and make their role clearer.

Potential improvements:
- show each visible tool with a concise capability summary
- emphasize differences between similar tools
- make it obvious that the planner is choosing task-to-tool bindings, not just generating a generic checklist

If the current planning prompt mostly lists tool names without enough semantic contrast, smaller models may naturally collapse toward one tool.

### 3. Add Lightweight Weak-Plan Validation
Introduce plan validation heuristics that operate after plan creation but before step execution.

These checks should be narrow, explainable, and avoid domain-specific hardcoding. Candidate heuristics:
- flag plans where all non-terminal tasks use the same tool even though multiple allowed tools are available
- flag plans where multiple consecutive tasks use the same tool but have significantly different stated intents
- flag plans where a task description implies a type of work that does not fit the bound tool's description
- flag plans where mission-critical capability categories appear to be missing even though matching tools are available

The validator should not attempt perfect semantic understanding. It should only catch obvious weak-plan patterns.

### 4. Decide the Runtime Policy for Weak Plans
Pick one of these policies for flagged weak plans:

Option A:
Reject the plan and request one bounded planning retry with targeted feedback.

Option B:
Accept the plan but emit a trace warning and metric.

Option C:
Use a severity threshold:
- reject clearly weak plans
- warn on merely suspicious plans

The likely best direction is Option C.

### 5. Add Trace Visibility for Plan Quality
Trace output should make plan-quality interventions visible.

Examples:
- `PLAN_VALIDATION_FAILED`
- `PLAN_RETRY_REQUESTED`
- `PLAN_QUALITY_WARNING`

This keeps debugging grounded in runtime evidence rather than prompt speculation.

## Suggested Implementation Shape
### Prompt Builder
Enhance the planning prompt builder with explicit planning rules such as:
- assign each task to the best-matching capability
- avoid creating multiple tasks that say different things but call the same tool without a clear reason
- do not assign summary/report/conclusion tasks to a tool unless that tool can actually produce the needed information
- use multiple allowed tools when the mission logically requires multiple kinds of evidence

### Planning Validator
Add a dedicated weak-plan validator component rather than burying this logic inside the execution engine. That keeps responsibilities cleaner and makes future evolution easier.

Suggested responsibilities:
- inspect proposed tasks, capability bindings, and visible tool metadata
- produce a structured list of warnings and errors
- support targeted planning retry prompts

### Retry Messaging
If the plan is rejected, the retry feedback should be concise and operational, for example:
- "The plan overuses `invoiceParser` for tasks with different intents."
- "The plan does not include any task using `expenseLookup`, even though duplicate checking appears to require comparing against prior expenses."
- "Task 3 is described as a report step, but the bound tool is an extraction tool."

The retry should avoid long philosophical explanations and focus on actionable corrections.

## Acceptance Criteria
- Planning prompts explicitly teach task-tool alignment and discourage shallow repetition.
- The framework can detect at least a narrow class of obviously weak plans before execution begins.
- A flagged weak plan produces either a targeted retry or an explicit warning according to the chosen runtime policy.
- Trace output clearly records when a plan was flagged, retried, or accepted with warning.
- Regression coverage includes repeated-tool overuse cases and missing-capability cases.
- The implementation remains generic and does not contain skill-specific logic for `duplicateInvoiceChecker`.

## Test Plan
Add tests at the planning layer that cover:

1. Repeated Tool Overuse
- mission has two meaningfully different allowed tools
- model returns a plan that binds every task to one tool
- validator flags or rejects the plan

2. Distinct Multi-Tool Plan
- same mission context
- model returns a plan that uses extraction and lookup tools appropriately
- validator accepts the plan

3. Suspicious Report Task Binding
- task description implies summary/report/conclusion
- bound tool is described as extraction-only
- validator flags the task

4. Retry Feedback Loop
- rejected plan produces focused retry feedback
- second planning attempt with improved binding is accepted

5. False Positive Guard
- a legitimate plan that uses the same tool multiple times for genuinely similar subtasks is not rejected automatically

## Risks
- Overly aggressive heuristics may reject valid plans and frustrate planning.
- Tool descriptions may not be rich enough today to support even lightweight semantic checks.
- Excessive prompt rules may bloat the planning prompt and hurt small-model performance.
- If the validator is too timid, it will not materially improve planning quality.

## Open Questions
- What runtime policy should weak-plan validation use by default: warn, retry, or severity-based?
- How much tool metadata is already available to the planner in a concise form?
- Should the validator rely only on manifest descriptions and capability names, or also on richer tool schema metadata when available?
- Do we want planning quality metrics so we can compare models and prompt versions over time?

## Recommendation
Proceed with a focused planning-quality pass in this order:

1. Strengthen the planning prompt with explicit task-tool alignment rules.
2. Improve the planner's view of allowed-tool semantics.
3. Add a small, explainable weak-plan validator with targeted retry support.
4. Emit trace events and tests so improvements can be measured.

This is the highest-leverage next step after the recent Phase 6 runtime-hardening work. It addresses the current quality bottleneck without prematurely committing the framework to a larger contract-design effort.
