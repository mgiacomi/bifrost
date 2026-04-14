# Feature: Concurrent Task Execution in HTN Step Loop

## Background
Currently, the `StepLoopMissionExecutionEngine` asks the LLM to emit a single `StepAction` in each iteration of the step loop. Even if multiple independent tasks have their dependencies fulfilled (i.e. they are simultaneously present in `plan.readyTasks()`), the execution engine runs them linearly, making sequential LLM prompts and singular sequential tool calls.

This creates significant latency. LLM capabilities natively understand parallelism, and our HTN `ExecutionPlan` intrinsically resolves logical dependency boundaries via the Directed Acyclic Graph (DAG) powered by `dependsOn`. Because the planner explicitly defines `readyTasks`, the framework inherently knows which tasks are logically safe to run simultaneously.

## Objective
Enable parallel execution of subskills/tools when multiple tasks live in `readyTasks`, leveraging background threads to dramatically cut total execution time. 

Crucially, **physical/state concurrency safety** (whether a particular skill is thread-safe or manipulates global state) must be delegated to the Skill Developer.

## Requirements

### 1. Skill YAML Overrides (Physical Safety)
We must introduce a flag in the `yaml` skill definition that signifies a skill is safe to be executed contemporaneously.
- Add an `allows_concurrent_execution` (or `batchable`) boolean to the core skill schema YAML properties.
- This flag **must default to `false`** for security and state-safety. A skill developer must opt-in.
- Update `YamlSkillDefinition` mapping logic to expose this configuration to the runtime.

### 2. Instructing the LLM (Prompt Updates)
Update `StepPromptBuilder.java`:
- When building the system prompt for the step execution model, explain that it **may** submit an array of multiple tool invocations simultaneously if (and only if) the chosen tasks are in the `Ready Tasks` pool.
- Let the model know that tasks should not be batched if their execution needs an active dependency output, but the HTN's `readyTasks` ensures that any tasks available are already structurally free of logical dependencies.

### 3. Step Action Parsing Adjustments
Update `StepLoopMissionExecutionEngine.java`:
- Update `parseStepAction` (and the `StepAction` model or equivalent list logic) so that the JSON markdown blocks parsed from the LLM can represent either a single object or a JSON array of `StepAction`s.
- Example structure:
  ```json
  [
    {
      "stepAction": "CALL_TOOL",
      "taskId": "invoice_t1",
      "toolName": "invoiceParser",
      "toolArguments": {...}
    },
    {
      "stepAction": "CALL_TOOL",
      "taskId": "invoice_t2",
      "toolName": "invoiceParser",
      "toolArguments": {...}
    }
  ]
  ```

### 4. Engine & Multithreaded Execution Updates
Update `StepLoopMissionExecutionEngine.executeOneStep()`, `executeToolAction()`, or how they communicate:
- Validate that *all* tool calls in the LLM's response point to tasks currently in `plan.readyTasks()`. Reject the action if the LLM attempts to batch a `blockedTask`.
- For each requested action, resolve the requested capability (`YamlSkillDefinition` or generic tool definition) and check if `allows_concurrent_execution == true`. If any action in the batch involves a non-batchable skill, gracefully downgrade to running them sequentially, or reject / truncate the batch.
- Submit the validated `CALL_TOOL` jobs asynchronously using Java threading (`ExecutorService`, `CompletableFuture`, or `Virtual Threads`). Note: the framework already maintains a `missionExecutor` threadpool for timeout guarding which could be reused, or a dedicated tool execution threadpool could be created.
- Await all requested futures.
- Write all tool completion results (`planningService.markToolCompleted`) to the plan state. Make sure `planningService` updates and `executionStateService.recordStepEvent` trace writes are thread-safe and properly handle concurrent updates if they are not already.

## Notes for the Implementing Agent
- **No Logical Depedency Workarounds Necessary**: The current HTN planner naturally guards against data dependency faults because dependent tasks will simply not appear in `readyTasks` until their parents are entirely completed. This isolation cleanly uncouples physical thread safety from logical workflow sequencing.
- The `FINAL_RESPONSE` step action should most likely remain mutually exclusive. The model should either execute batches of tools, OR return a final response. Validate and reject mixing.
- You do not have previous context from this conversation. Ensure you review `StepLoopMissionExecutionEngine.java` thoroughly before refactoring, specifically around the `executeOneStep()` methodology.
