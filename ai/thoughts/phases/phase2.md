# Phase 2 - Core Engine and Spring AI Integration

## Goal
Implement the core execution kernel directly into the Spring Boot Starter, leveraging Spring AI `ChatClient` and `ChatModel` for sub-agent behavior.

## Primary Outcomes
- The Spring application context defines the core domain model and execution rules.
- `CapabilityRegistry` is populated by scanning `@SkillMethod` annotations on Spring beans.
- A session-aware execution context can route `callMethod` to beans and `callSkill` to Spring AI.

## Scope
- `BifrostSession` and `ExecutionFrame` lifecycle management.
- `CapabilityRegistry` implementing bean post-processing for auto-discovery.
- Utilizing Spring AI's native tool calling framework.

## Detailed Tasks
### 1. Capability Discovery
- Define `@SkillMethod` annotation for scanning custom java skills.
- Implement a Spring `BeanPostProcessor` that inspects beans, generates metadata, and registers them to the `CapabilityRegistry`.
- Map Bifrost method signatures seamlessly to Spring AI's expected `java.util.function.Function` definitions, allowing Spring AI tools to utilize native Bifröst functions effortlessly.

### 2. Execution Orchestration
- Create `ExecutionCoordinator` component.
- **Internal MCP-Like Routing:** Connect the LLM responses to the internal capabilities without fully standing up an external MCP Server. The coordinator acts as the bridge.
- **Planning Mode & Task Tracking:** Implement a "Task List" generation step to anchor the LLM's cognitive process. This mode is enabled globally by default, but can be overridden on a per-skill basis. Store this list in the `BifrostSession`. The coordinator tracks progress sequentially without preemptive static analysis of the plan, relying on standard RBAC checks at the exact moment a tool is called.
- **BifrostExceptionTransformer:** When a `@SkillMethod` throws an exception, intercept it and translate it into a clean, "AI-Readable" string (e.g. `ERROR: DatabaseDownException. HINT: ...`) to enable meaningful model-driven error recovery instead of confusing the LLM with Java stack traces.
- The coordinator resolves `ref://` pointers injected into inputs, pulling bytes from Spring's `ResourceLoader` into memory, preventing manual VFS management within normal domain logic beans.

### 3. Session Lifecycle Management
- Establish `BifrostSession` thread-local context or scoped bean structure.
- **Virtual Thread Concurrency Control:** Utilize `ReentrantLock` instead of `synchronized` blocks within the core engine to avoid pinning carrier threads while waiting for LLM responses.
- Track `ExecutionFrame` POJOs natively using Java Virtual Threads. Introduce a Fail-Fast strategy (restarting the session on hard failure) rather than building complex persistence.
- Enforce `MAX_DEPTH` recursion limits inside the frame push sequence.
- Initialize the `ExecutionJournal` mechanism, ensuring it is serializable to JSON so that it acts as a "Save Game" state ready for future JDBC/Redis persistence implementations.

### 4. Direct ChatClient binding
- Pre-configure Spring AI `ChatClient` instances for `heavy` and `light` sub-agents based on the developer's properties (`application.yml`).

## Deliverables
- Functional `CapabilityRegistry` with `@SkillMethod` scanner.
- Thread-aware `BifrostSession` and recursive frame tracker.
- Spring AI mapped prompt execution core.

## Exit Criteria
- Methods annotated with `@SkillMethod` are auto-configured into the tool registry.
- A basic sub-agent can execute using Spring AI `ChatClient` through the Bifrost API.
