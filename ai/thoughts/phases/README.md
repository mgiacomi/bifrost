## Bifrost: The Agentic LLM-OS Framework (Master Spec)

## Project Stance
This library is still in active development.

For this ticket, we should optimize for a cleaner long-term architecture rather than preserving immature APIs, endpoints, or internal seams.

Explicit guidance:

- breaking changes are acceptable
- we do not need to preserve legacy code paths just because they already exist
- compatibility shims should be avoided unless they materially simplify the migration
- reducing technical debt is more important than minimizing churn

The implementation should prefer replacing weak or transitional patterns over layering new abstractions on top of them.

## Phase Documents
- [Phase 1](C:/opendev/code/bifrost/ai/thoughts/phases/phase1.md)
- [Phase 2](C:/opendev/code/bifrost/ai/thoughts/phases/phase2.md)
- [Phase 3](C:/opendev/code/bifrost/ai/thoughts/phases/phase3.md)
- [Phase 4](C:/opendev/code/bifrost/ai/thoughts/phases/phase4.md)
- [Phase 5](C:/opendev/code/bifrost/ai/thoughts/phases/phase5.md)
- [Phase 6](C:/opendev/code/bifrost/ai/thoughts/phases/phase6.md)
- [Phase 7](C:/opendev/code/bifrost/ai/thoughts/phases/phase7.md)

### 1. The Core Paradigm: LLM-as-CPU
Bifrost treats the LLM as a reasoning CPU and the surrounding application as an operating system (kernel). This shifts the focus from "chatbots" to agentic computing.  
Bifrost is the bridge between the LLM and the Spring Boot application. It leverages Spring AI as the foundation for model interactions. It is part of a suite of products created by lokiscale.com

- **LLM (CPU):** The reasoning engine that processes instructions and triggers interrupts.
- **App Code (Kernel/OS):** Spring Boot environment handling security, DI, and execution routing.
- **Skills (User-Space Apps):** Modular "Capability Packages" with YAML manifests, structured instructions, and execution mappings.
- **Context Window (RAM):** Short-term memory kept lean via pointer-based memory management. It utilizes **Recursive Summarization** at the session level. When an `ExecutionFrame` closes, it returns a one-sentence summary of its reasoning to the Master LLM to keep the context dense and prevent rot.
- **Internal Routing Layer:** Bifrost natively routes LLM instructions internally using an MCP-inspired capability mapping, rather than requiring an external MCP server.

### 2. Project Structure (Spring-Native Architecture)
Bifrost is a modern Java project designed as a Spring Boot Starter. By leaning fully into the Spring ecosystem, we dramatically reduce boilerplate, utilizing Spring AI for prompt execution and tool calling, Spring Security for RBAC, and Spring's `Resource` abstractions for file systems.

- **bifrost-spring-boot-starter:** The core library containing the execution loop, YAML parsing, `CapabilityRegistry`, and auto-configuration layer providing native Spring support and `@SkillMethod` bean scanning.
- **bifrost-sample:** A demo Spring Boot application validating the starter's configuration and features.

### 3. The Instruction Set (MVP Opcodes)
Bifrost natively exposes four primary operations to the LLM:

- **callMethod:** Fast, deterministic execution of a YAML skill whose `mapping.target_id` resolves to a Spring bean method flagged with `@SkillMethod`.
- **callMethod failure handling (current iteration):** Exceptions from deterministic `@SkillMethod` execution should be normalized at Bifrost's own invocation boundary so the LLM receives an AI-readable tool result rather than a raw Java stack trace.
- **callSkill:** Top-level YAML skills execute through `ExecutionCoordinator`, which creates a skill-specific Spring AI `ChatClient`, applies any configured advisors, and runs the mission prompt. YAML skills without `mapping.target_id` can execute as top-level skills through this path, but they are not registered as nested callable invokers through the capability registry.
- **readData / writeData:** `Resource`-backed Spring interfaces that let the LLM store data and pass `ref://...` pointers between skills to prevent context rot.

### 4. The Skill Architecture (YAML Manifest)
Skills are defined in pure YAML: the YAML is the LLM-facing skill contract and context/parameterization, while Java methods and Spring AI sub-agents are implementation back-ends. `@SkillMethod` marks implementation targets that YAML may link to; it is not itself the user-facing skill contract.

**Zone A: Public Manifest (visible to LLM)**
- Name & description
- Structured instructions: `persona`, `input_context`, `logic_steps`, `output_format`
- Assets & sub-skills

**Zone B: Private Manifest (visible only to kernel)**
- `rbac`: Required Spring Security roles for access
- `tags`: For contextual filtering
- `mapping`: `target_id` linking the skill to a Spring bean implementation discovered through `@SkillMethod`. When `target_id` is present, the YAML-defined skill remains the registry-facing capability while delegating invocation to the discovered Java target. When `target_id` is omitted, the skill uses the LLM-backed execution path for top-level execution.
- `model`: Required exact model name, matching a configured framework model entry in `application.yml`
- `thinking_level`: Optional execution hint, validated against the selected model's supported thinking levels
- `linter`: Optional verification gate that auto-validates output and provides "Success/Fail + Hint" feedback
- `planning_mode`: Boolean to override the global planning mode behavior for this specific skill

### 5. Security & RBAC
Security is enforced at two points: discovery (filtering visible tools injected into the prompt context) and execution.
- **Spring Security integration:** The core engine maps `UserPrincipal` and authorities into the execution frame.
- **Confused deputy protection:** RBAC in the skill manifest blocks unauthorized tool calls even if the Master LLM is tricked by prompt injection.

### 6. Execution Lifecycle & Model Selection
- **Planning Mode (Cognitive Anchor):** Enabled by default via global configuration (`application.yml`), but can be overridden at the skill level. Before execution begins, the LLM outputs a "Task List" (Flight Plan). This acts as a cognitive anchor to prevent drift. The execution coordinator iterates through this list, updating status and tracking progress transparently in the `ExecutionJournal`, while RBAC is simply enforced dynamically at the point of tool execution.
- **Reproducibility by default:** LLM-backed YAML skills declare an explicit `model` and may optionally declare `thinking_level`. The framework validates these values against the configured model catalog at boot.
- **Thinking-level defaults:** If a skill omits `thinking_level` and the selected model supports thinking, Bifrost uses `medium`. If the model does not support thinking, no thinking level is applied.
- **Provider-aware resolver/factory execution:** Bifrost resolves the effective model settings for each skill through a shared resolver/factory rather than a small set of abstract profile tiers. The framework model catalog can describe providers such as OpenAI, Anthropic Claude, Gemini, and Ollama behind one consistent YAML contract.
- **Concurrency:** `callSkill` utilizes Java Virtual Threads natively supported by modern Spring environments for efficient, non-blocking asynchronous execution.
- **Telemetry:** Built on Micrometer, capturing reasoning logs and performance metrics for isolated sub-agents.
- **Current exception boundary:** Deterministic method-execution error transformation lives at the shared `SkillMethodBeanPostProcessor` invoker boundary, while top-level YAML execution flows through `ExecutionCoordinator` and `MissionExecutionEngine`.

### 7. The Session Model (BifrostSession)
When a developer or system calls the library, it runs under a `BifrostSession`. This object lives for the duration of the "Mission" and holds:
1. **Identity & Governance**: The unique identifier and attached Spring `SecurityContext`.
2. **Resource Isolation**: A session-scoped view of the virtual file system mapped to Spring's `ResourceLoader`.
3. **The Execution Stack**: Tracks recursive `callSkill` frames and applies limits (`BifrostStackOverflowException`).
4. **Telemetry & "Thinking Logs"**: An `ExecutionJournal` capturing reasoning traces and tool outputs.
