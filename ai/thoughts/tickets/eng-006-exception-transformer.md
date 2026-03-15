# Ticket: eng-006-exception-transformer.md
## Issue: Implement BifrostExceptionTransformer

### Context
When native `@SkillMethod` beans throw Java Exceptions (e.g., `NullPointerException`, `EntityNotFoundException`), surfacing a massive raw Java stack trace directly into the LLM context ruins the reasoning space and pollutes the prompt.

### Requirements
1. **`BifrostExceptionTransformer` Interface:**
   - Strategy interface for transforming `Throwable` down to a human/AI-readable string.
2. **Default Implementation:**
   - Generate an output string like: `ERROR: [Exception Class Name]. HINT: [Exception Message]`.
   - Suppress the raw stack trace entirely from the LLM return payload.
3. **Execution Hook Point:**
   - Identify the catch block around the `java.util.function.Function` reflection invocation (from eng-002). Catch all `Exception`s, route them through the transformer, and return the AI-readable string *as the tool execution result* to the LLM (so the LLM knows the tool failed, rather than crashing the system entirely).

### Acceptance Criteria
- A test explicitly throws an exception from a mocked `@SkillMethod`.
- The exception is caught at the framework boundary, fed through the transformer, and returned as a string.
- Logs the *actual* stack trace to the system log (SLF4J) but ensures the returned payload cleanly omits it.
