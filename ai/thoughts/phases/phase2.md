# Phase 2 - Core Engine and Spring AI Integration

## Goal
Implement the core execution kernel directly into the Spring Boot Starter, leveraging Spring AI `ChatClient` and `ChatModel` for sub-agent behavior.

## Primary Outcomes
- The Spring application context defines the core domain model and execution rules.
- `CapabilityRegistry` is populated by scanning `@SkillMethod` annotations on Spring beans as implementation targets.
- A session-aware execution context is designed to route YAML-defined `callMethod` skills to discovered bean targets and `callSkill` skills to Spring AI; in the current implementation, the deterministic YAML-to-`@SkillMethod` route is working while the YAML-backed `callSkill` branch remains planned but not fully implemented.

## Scope
- `BifrostSession` and `ExecutionFrame` lifecycle management.
- `CapabilityRegistry` implementing bean post-processing for auto-discovery.
- Utilizing Spring AI's native tool calling framework.

## Detailed Tasks
### 1. Capability Discovery
- Define `@SkillMethod` annotation for scanning custom Java implementation targets that YAML skills may reference.
- Implement a Spring `BeanPostProcessor` that inspects beans, generates metadata, and registers them to the `CapabilityRegistry`.
- Map Bifrost method signatures seamlessly to Spring AI's expected `java.util.function.Function` definitions, allowing Spring AI tools to utilize native Bifrost functions effortlessly.

### 2. Execution Orchestration
- Create `ExecutionCoordinator` component.
- **Internal MCP-Like Routing:** Connect the LLM responses to the internal capabilities without fully standing up an external MCP Server. The coordinator acts as the bridge.
- **Planning Mode & Task Tracking:** Implement a "Task List" generation step to anchor the LLM's cognitive process. This mode is enabled globally by default, but can be overridden on a per-skill basis. Store this list in the `BifrostSession`. The coordinator tracks progress sequentially without preemptive static analysis of the plan, relying on standard RBAC checks at the exact moment a tool is called.
- **BifrostExceptionTransformer:** When a `@SkillMethod` throws an exception, intercept it and translate it into a clean, "AI-Readable" string (e.g. `ERROR: DatabaseDownException. HINT: ...`) to enable meaningful model-driven error recovery instead of confusing the LLM with Java stack traces.
- **Current Iteration Hook Point:** Until `ExecutionCoordinator` exists in code, implement exception transformation at the current `SkillMethodBeanPostProcessor.invokeToolCallback(...)` boundary, which is the shared path for direct `@SkillMethod` invocation and deterministic YAML-to-`@SkillMethod` execution.
- **Dependency Boundary:** Keep this exception handling defined at the Bifrost boundary rather than relying on undocumented Spring AI internal exception wrappers, so the behavior remains stable across Spring AI upgrades.
- The coordinator resolves `ref://` pointers injected into inputs, pulling bytes from Spring's `ResourceLoader` into memory, preventing manual VFS management within normal domain logic beans.

### 3. Session Lifecycle Management
- Establish `BifrostSession` thread-local context or scoped bean structure.
- **Virtual Thread Concurrency Control:** Utilize `ReentrantLock` instead of `synchronized` blocks within the core engine to avoid pinning carrier threads while waiting for LLM responses.
- Track `ExecutionFrame` POJOs natively using Java Virtual Threads. Introduce a Fail-Fast strategy (restarting the session on hard failure) rather than building complex persistence.
- Enforce `MAX_DEPTH` recursion limits inside the frame push sequence.
- Initialize the `ExecutionJournal` mechanism, ensuring it is serializable to JSON so that it acts as a "Save Game" state ready for future JDBC/Redis persistence implementations.

### 4. Skill Model Resolution
- Bind a framework-managed, provider-aware model catalog from `application.yml`, including provider identity, exact provider model names, and optional supported thinking levels.
- Validate YAML skill execution settings at boot so that each LLM-backed skill references a known configured model and an allowed thinking level.
- Resolve skill-specific `ChatClient` instances through a shared resolver/factory based on validated skill configuration, rather than fixed `heavy` and `light` bindings.
- Keep YAML as the source of the LLM-facing skill contract while `@SkillMethod` remains discoverability and implementation-linking metadata.
- Preserve the YAML-defined capability registration when `mapping.target_id` delegates execution to a discovered `@SkillMethod` target.
- Document the LLM-backed YAML branch as intended architecture until the runtime invoker is implemented, rather than implying that YAML-only `callSkill` execution is already complete.

## Deliverables
- Functional `CapabilityRegistry` with `@SkillMethod` scanner for implementation targets.
- Thread-aware `BifrostSession` and recursive frame tracker.
- Spring AI mapped prompt execution core.

## Exit Criteria
- Methods annotated with `@SkillMethod` are auto-configured into the tool registry as implementation targets.
- YAML-defined deterministic skills can execute through discovered `@SkillMethod` targets, and documentation clearly marks YAML-only sub-agent execution via Spring AI as a remaining implementation step.
- `@SkillMethod` failures are transformed at the existing Bifrost invocation boundary and returned as AI-readable tool results without exposing raw stack traces to the LLM payload.
