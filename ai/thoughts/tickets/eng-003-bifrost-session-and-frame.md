# Ticket: eng-003-bifrost-session-and-frame.md
## Issue: Implement BifrostSession and ExecutionFrame Lifecycle

### Context
Bifröst sessions hold the thread-local state or scoped session state during LLM operation. Virtual Threads are in use, meaning concurrency mechanisms need to be Virtual Thread friendly.

### Requirements
1. **`BifrostSession` Context:**
   - Implement `BifrostSession`. This must live for the duration of a specific task or mission. 
   - Add fields for a unique session ID.
   - Include a `ReentrantLock` for state modifications—**avoid** `synchronized` blocks to prevent pinning Virtual Thread carrier threads during IO operations.
2. **`ExecutionFrame` and Stack Tracking:**
   - Create an `ExecutionFrame` POJO tracking method routing and parameters.
   - `BifrostSession` pushes and pops `ExecutionFrame`s as a stack (recursion tracking).
   - Enforce a configurable `MAX_DEPTH` (e.g. 5 or 10) to prevent deep unconstrained recursion or loops. Throw `BifrostStackOverflowException` if the limit is exceeded.
3. **Thread Scoping Strategy:**
   - Implement a mechanism (ThreadLocal, ScopedValue in Java 21, or custom Spring Scope) to effortlessly retrieve `BifrostSession.getCurrentSession()` statically or inject it safely per execution flow without passing it through all method signatures.

### Acceptance Criteria
- `BifrostSession` uses `ReentrantLock` for thread-safe mutations.
- `ExecutionFrame` push/pop is implemented with tested `MAX_DEPTH` fail-fast circuit breakers.
- A static or injected access pattern correctly resolves the session boundaries in unit testing across multiple concurrent virtual threads.
