# Ticket: eng-006-exception-transformer.md
## Issue: Implement BifrostExceptionTransformer

### Context
When native `@SkillMethod` beans throw Java Exceptions (e.g., `NullPointerException`, `EntityNotFoundException`), surfacing a massive raw Java stack trace directly into the LLM context ruins the reasoning space and pollutes the prompt.

### Iteration Guidance
- This work is intended to be completed in the current iteration.
- The implementation point for this iteration is the existing framework boundary in `SkillMethodBeanPostProcessor.invokeToolCallback(...)`, since that boundary already covers both direct `@SkillMethod` invocation and YAML skills that reuse `target.invoker()`.
- The design should avoid depending on undocumented Spring AI internal exception types. Catch and transform failures at the Bifrost-owned boundary, and keep the transformation logic behind a dedicated interface so it can later move behind an `ExecutionCoordinator` if that component is introduced.

### Requirements
1. **`BifrostExceptionTransformer` Interface:**
   - Strategy interface for transforming `Throwable` down to a human/AI-readable string.
2. **Default Implementation:**
   - Unwrap nested causes so the returned payload preserves the underlying business exception type/message rather than a framework wrapper when possible.
   - Generate an output string like: `ERROR: [Exception Class Name]. HINT: [Exception Message]`.
   - Suppress the raw stack trace entirely from the LLM return payload.
3. **Execution Hook Point:**
   - For this iteration, implement the hook at the existing catch boundary in `SkillMethodBeanPostProcessor.invokeToolCallback(...)`.
   - Catch framework-visible execution failures there, route them through the transformer, and return the AI-readable string *as the tool execution result* to the LLM (so the LLM knows the tool failed, rather than crashing the system entirely).
   - Keep the behavior defined in Bifrost terms rather than tying the implementation to a specific Spring AI wrapper class.

### Acceptance Criteria
- A test explicitly throws an exception from a mocked `@SkillMethod`.
- The exception is caught at the framework boundary, fed through the transformer, and returned as a string.
- The returned payload reflects the unwrapped business exception class/message when the framework surfaces the failure through wrapper exceptions.
- Logs the *actual* stack trace to the system log (SLF4J) but ensures the returned payload cleanly omits it.
- The implementation works for both directly discovered `@SkillMethod` capabilities and YAML deterministic skills that resolve through `mapping.target_id`.
