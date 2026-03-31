---
date: 2026-03-30T21:52:01-07:00
researcher: Unknown
git_commit: a4fe07632e3451907b4b48dfb743d99d825dd94e
branch: main
repository: bifrost
topic: "Research current codebase state for eng-skill-input-contracts-and-skill-template"
tags: [research, codebase, skills, yaml-skills, capability-routing, step-loop]
status: complete
last_updated: 2026-03-30
last_updated_by: Unknown
---

# Research: Current Codebase State for Skill Input Contracts and SkillTemplate

**Date**: 2026-03-30T21:52:01-07:00
**Researcher**: Unknown
**Git Commit**: a4fe07632e3451907b4b48dfb743d99d825dd94e
**Branch**: main
**Repository**: bifrost

## Research Question
Document the current codebase state relevant to `ai/thoughts/tickets/eng-skill-input-contracts-and-skill-template.md`, focusing on how skill input contracts, YAML skill manifests, Java-backed skills, execution routing, step-loop validation, and sample invocation APIs work today.

## Summary
The current codebase has explicit manifest and runtime support for `output_schema` and `evidence_contract`, but no corresponding `input_schema` field or runtime input-contract abstraction in the YAML skill model. `YamlSkillManifest` defines output schema, evidence contract, mapping, planning, and linter settings, while `YamlSkillCatalog` validates required manifest fields, output schema structure, evidence mappings, linter configuration, and model selection during startup (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:37`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:116`).

YAML skills are registered as capabilities through `YamlSkillCapabilityRegistrar`. Mapped YAML skills reuse the mapped Java target's invoker and tool input schema, while unmapped YAML skills receive `CapabilityToolDescriptor.generic(...)`, which is backed by a generated JSON schema for `Map<String, Object>` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:44`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolDescriptor.java:14`).

Java `@SkillMethod` capabilities already expose method-signature-derived tool schemas through `SkillMethodBeanPostProcessor`, and invocation remains map-based through `CapabilityInvoker.invoke(Map<String, Object>)` and `CapabilityExecutionRouter.execute(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:70`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityInvoker.java:5`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:45`).

Nested execution for unmapped YAML skills currently turns the input map into an objective string, `Execute YAML skill '...' using these tool arguments: ...`, and hands that string to `ExecutionCoordinator`. `StepActionValidator` validates task/tool consistency and only checks top-level required fields from the visible tool's JSON schema. `StepPromptBuilder` lists tool names, task bindings, and final-response output schema examples, but it does not render a concrete tool-argument example from the tool's input schema (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:55`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepActionValidator.java:108`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:83`).

The sample application still exposes raw controller endpoints that look up `CapabilityMetadata`, open sessions with `BifrostSessionRunner`, and call `executionRouter.execute(...)` directly. It also exposes debug/session inspection HTTP endpoints. A repo-wide search found no current `SkillTemplate`, `DefaultSkillTemplate`, `SkillExecutionView`, or `SkillInputValidationException` classes.

## Detailed Findings

### YAML Manifest and Catalog Model
- `YamlSkillManifest` includes `name`, `description`, `model`, `thinking_level`, `allowed_skills`, `rbac_roles`, `planning_mode`, `max_steps`, `linter`, `output_schema`, `output_schema_max_retries`, `evidence_contract`, and `mapping.target_id` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:16`).
- The manifest defines nested classes for `LinterManifest`, `RegexManifest`, `MappingManifest`, `EvidenceContractManifest`, and `OutputSchemaManifest` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:152`).
- `OutputSchemaManifest` is the schema-shaped manifest model currently reused for output contracts, including `type`, `properties`, `required`, `additionalProperties`, `items`, `enum`, `description`, and `format` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:251`).
- There is no `@JsonProperty("input_schema")` field or input-specific manifest accessor on `YamlSkillManifest`; `YamlSkillDefinition` exposes `outputSchema()` and `outputSchemaMaxRetries()` helpers but no parallel input-contract helper (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:37`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:39`).
- `YamlSkillCatalog.afterPropertiesSet()` discovers configured YAML resources, loads each manifest, validates it, and stores typed `YamlSkillDefinition` entries keyed by skill name (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:65`).
- During load, the catalog validates required `name`, `description`, and `model` fields, then validates output schema, evidence contract, and linter settings before resolving model/provider configuration (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:116`).
- Output schema validation enforces the supported subset and root-object requirement, fills in a default `output_schema_max_retries`, and defaults `additionalProperties` to `false` for object nodes (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:207`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:288`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:317`).
- Evidence-contract validation requires `output_schema` and validates claim/tool mappings against output-schema property names (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:230`).

### Capability Registration and Tool Schema Exposure
- `YamlSkillCapabilityRegistrar` registers every YAML skill as a `CapabilityMetadata` with `CapabilityKind.YAML_SKILL`, a YAML-derived capability id, `SkillExecutionDescriptor.from(...)`, and the YAML skill's `mappingTargetId()` stored as `mappedTargetId` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:27`).
- For mapped YAML skills, `resolveInvoker()` looks up the mapped target capability by `mapping.target_id` and reuses the target's invoker (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:44`).
- For unmapped YAML skills, `resolveInvoker()` returns an invoker that throws `UnsupportedOperationException`, while actual execution is handled elsewhere through the YAML branch in `CapabilityExecutionRouter` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:55`).
- Tool descriptor resolution is split the same way:
- Mapped YAML skills create a new `CapabilityToolDescriptor` using the YAML skill's name/description plus the mapped target's `tool().inputSchema()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:61`).
- Unmapped YAML skills use `CapabilityToolDescriptor.generic(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:63`).
- `CapabilityMetadata` has fields for `invoker`, `kind`, `tool`, and nullable `mappedTargetId`, but no separate runtime input-contract field beyond the tool descriptor (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java:8`).
- `CapabilityToolDescriptor.generic(...)` is built from a Spring AI JSON schema generated for `Map<String, Object>`, which is the generic input shape used when no more specific tool schema is attached (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolDescriptor.java:14`).

### Java `@SkillMethod` Capabilities
- `SkillMethodBeanPostProcessor` scans beans for methods annotated with `@SkillMethod` and registers them as capabilities after initialization (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:58`).
- For each method, it creates a Spring AI `ToolDefinition` whose `inputSchema` comes from `JsonSchemaGenerator.generateForMethodInput(method)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:70`).
- The generated tool schema is then stored on `CapabilityMetadata` through `new CapabilityToolDescriptor(..., toolDefinition.inputSchema())` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:78`).
- Runtime invocation remains map-first through `CapabilityInvoker`, which is a functional interface with `Object invoke(Map<String, Object> arguments)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityInvoker.java:5`).
- `invokeSkillMethod(...)` binds the map into method parameters using Jackson conversion and special-case handling for `Resource`, `InputStream`, `byte[]`, `String`, collections, arrays, and nested object materialization (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:93`).
- The method result is serialized to JSON text before returning (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:99`).

### Root and Nested Execution Routing
- `CapabilityExecutionRouter.execute(...)` is the common execution seam taking `CapabilityMetadata`, `Map<String, Object> arguments`, `BifrostSession`, and optional `Authentication` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:45`).
- It normalizes `null` arguments to `Map.of()` and checks RBAC through `AccessGuard` before routing (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:52`).
- If the capability is a YAML skill with `mappedTargetId == null`, the router snapshots the parent plan/evidence, builds an objective string from the tool arguments, and calls `ExecutionCoordinator.execute(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:55`).
- The objective string is produced by `objectiveFor(...)` as:
- `Execute YAML skill '%s' using these tool arguments:`
- followed by the JSON serialization of the arguments map (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:71`).
- For all other capabilities, including mapped YAML skills and Java methods, the router resolves refs and invokes the capability through `capability.invoker().invoke(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:68`).
- `ExecutionCoordinator.execute(...)` requires the YAML skill to exist in `YamlSkillCatalog`, resolves the root capability from `CapabilityRegistry`, opens a mission frame, clears plan/evidence state, builds the visible tool surface, and delegates to either the standard mission engine or the step-loop engine depending on `planningModeExplicitlyEnabled()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:54`).

### Step-Loop Prompting and Validation
- `StepActionValidator.validate(...)` rejects null actions, requires `CALL_TOOL` or `FINAL_RESPONSE`, and dispatches to call-tool or final-response validation (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepActionValidator.java:27`).
- `validateCallTool(...)` verifies:
- there are incomplete tasks
- `taskId` and `toolName` are present
- the task exists and is ready
- the proposed tool is visible
- the proposed tool matches the task's bound `capabilityName()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepActionValidator.java:47`).
- `validateRequiredToolArguments(...)` then reads the visible tool's JSON schema and checks only object-root `required` fields against the proposed `toolArguments` map (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepActionValidator.java:108`).
- The validator does not contain code for nested required fields, enum validation, unknown-field checks, or type coercion; the implemented schema check is the top-level `required` array scan (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepActionValidator.java:124`).
- `StepPromptBuilder.buildStepPrompt(...)` assembles the step-loop system prompt from plan state, execution summary, last tool result, available tool names, and task-to-tool instructions (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:25`).
- The prompt explicitly tells the model that the ready task is already bound to a specific tool and says not to use the mission skill name as `toolName` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:131`).
- `formatCurrentStepInstructions(...)` renders exact `taskId` and `toolName` guidance for one or more ready tasks (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:270`).
- `sanitizeObjective(...)` strips the `Execute YAML skill '...' using these tool arguments:` wrapper into `Use these mission inputs:` when possible, so the tool-argument payload becomes mission context text in the prompt (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:199`).
- For final responses, `appendOutputSchemaGuidance(...)` renders a schema example and required top-level output fields from `output_schema` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:223`).
- The prompt does not render a parallel example block for a selected tool's input schema; tool arguments remain described generically as `"toolArguments": { <arguments for this tool> }` plus task/tool-name binding rules (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:120`).

### Auto-Configuration and Public Invocation Surface
- `BifrostAutoConfiguration` wires the current YAML and execution stack by exposing beans for `YamlSkillCatalog`, `YamlSkillCapabilityRegistrar`, `CapabilityExecutionRouter`, `StepLoopMissionExecutionEngine`, and `ExecutionCoordinator` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:124`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:133`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:171`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:378`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:402`).
- A repo-wide search returned no Java classes named `SkillTemplate`, `DefaultSkillTemplate`, `SkillExecutionView`, or `SkillInputValidationException`, and `BifrostAutoConfiguration` contains no bean method for those types.

### Sample App Examples
- The sample app still performs direct capability lookup and map-based execution from HTTP endpoints:
- `/expenses` looks up `getLatestExpenses` and calls `sessionRunner.callWithNewSession(session -> executionRouter.execute(metadata, Map.of(), session, null))` (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java:45`).
- `/invoice/parse` looks up `invoiceParser` and passes `Map.of("payload", invoiceText)` into `executionRouter.execute(...)` (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java:89`).
- `/invoice/check-duplicate` does the same for `duplicateInvoiceChecker` (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java:147`).
- The same controller also exposes `/debug/bifrost/sessions`, `/debug/bifrost/sessions/{sessionId}`, and `/debug/bifrost/sessions/{sessionId}/journal`, which return session/trace/journal information directly from tracked `BifrostSession` instances (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java:57`).
- The sample YAML skills show three current patterns:
- `expenseLookup` is a mapped YAML skill with `mapping.target_id: expenseService#getLatestExpenses` and no output schema (`bifrost-sample/src/main/resources/skills/expense_lookup.yml:1`).
- `invoiceParser` is an unmapped YAML skill with `output_schema`, `output_schema_max_retries`, and a regex linter, but no input schema (`bifrost-sample/src/main/resources/skills/invoice_parser.yml:1`).
- `duplicateInvoiceChecker` is a planning-mode unmapped YAML skill with `allowed_skills`, `evidence_contract`, and `output_schema`, but no input schema (`bifrost-sample/src/main/resources/skills/duplicate_invoice_checker.yml:1`).
- `ExpenseService#getLatestExpenses()` is a Java `@SkillMethod` with no parameters, which means the mapped `expenseLookup` skill inherits the target tool schema produced for that method (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/ExpenseService.java:12`).

## Code References
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:37` - manifest support for `output_schema`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:43` - manifest support for `evidence_contract`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:251` - current schema manifest model used for output schema trees.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:116` - startup manifest loading and validation sequence.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:207` - output-schema validation entrypoint.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:230` - evidence-contract validation entrypoint.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:39` - typed helper exposing output schema from the manifest.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:44` - mapped YAML skills reuse target invokers.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:61` - mapped YAML skills reuse target input schema; unmapped skills get generic descriptors.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityToolDescriptor.java:14` - generic input schema generated for `Map<String, Object>`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:70` - Java method input schema generation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:55` - nested unmapped YAML skill execution path.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:71` - tool arguments serialized into the objective string.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:54` - YAML mission execution entrypoint.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepActionValidator.java:108` - tool-argument schema validation logic.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:120` - generic `toolArguments` placeholder in the prompt.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:223` - output-schema example rendering for final responses.
- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java:52` - direct Java-side execution through `executionRouter.execute(...)`.
- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java:57` - sample debug/session inspection endpoints.
- `bifrost-sample/src/main/resources/skills/expense_lookup.yml:5` - mapped YAML skill example using `target_id`.
- `bifrost-sample/src/main/resources/skills/invoice_parser.yml:5` - pure YAML skill with output schema and no input schema.
- `bifrost-sample/src/main/resources/skills/duplicate_invoice_checker.yml:11` - evidence-aware planning YAML skill with no input schema.

## Architecture Documentation
The current architecture distinguishes between YAML skill definitions, registered capability metadata, and execution routing. YAML manifests are loaded into `YamlSkillDefinition` records at startup by `YamlSkillCatalog`, with startup-time validation focused on declared manifest fields and output/evidence configuration. `YamlSkillCapabilityRegistrar` then projects those YAML definitions into generic `CapabilityMetadata` records so they can participate in the same registry and tool-surface flow as Java `@SkillMethod` capabilities.

Input shape authority currently sits in tool schemas rather than in a Bifrost-owned input-contract type. Java capabilities get tool schemas from Spring AI's reflected method-input generator. Mapped YAML skills reuse the mapped target's tool schema. Unmapped YAML skills publish the generic `Map<String, Object>` schema. The execution layer routes based on `CapabilityKind` and `mappedTargetId`: unmapped YAML skills are re-entered through `ExecutionCoordinator`, while Java methods and mapped YAML skills run through `CapabilityInvoker`.

Step-loop prompting and validation consume those same visible tool definitions. Prompting currently emphasizes plan state, task-to-tool binding, and final-response output schema. Validation currently uses the selected tool's JSON schema only to enforce top-level required fields. The sample application demonstrates the current public usage style by calling `CapabilityExecutionRouter` directly from controllers and by exposing direct session debugging endpoints.

## Historical Context (from ai/thoughts/)
- `ai/thoughts/phases/phase7.md` - earlier design note describing Phase 7 as the skill-input-contract and invocation-ergonomics phase, including explicit questions around input contract authority, nested invocation semantics, and a possible `SkillTemplate`.
- `ai/thoughts/tickets/eng-skill-input-contracts-and-skill-template.md` - ticket note describing the same area with more concrete implementation detail and acceptance criteria.

## Related Research
No prior documents were present under `ai/thoughts/research/` at the time of this research.

## Current Gaps to Implement
- Add `SkillTemplate` as the new public execution API under `com.lokiscale.bifrost.skillapi`; the current codebase does not expose that API today.
- Add `SkillInputContract` under `com.lokiscale.bifrost.runtime.input` as the runtime representation of normalized effective input schema; the current codebase does not define that type today.
- Framework-owned validation and prompt rendering currently rely on tool JSON schema strings attached to `CapabilityToolDescriptor`; this ticket defines work to introduce a framework-owned input-contract layer instead of continuing to rely solely on those strings.
