---
date: 2026-05-18T01:57:26-07:00
researcher: GPT-5
git_commit: 07a15a223fc11e9945c6948586fbc50ae4c6aa77
branch: main
repository: bifrost
topic: "First-Class Prompt Instructions for YAML Skills"
tags: [research, codebase, yaml-skills, prompts, output-schema, planning, step-loop, traces]
status: complete
last_updated: 2026-05-18
last_updated_by: GPT-5
---

# Research: First-Class Prompt Instructions for YAML Skills

**Date**: 2026-05-18T01:57:26-07:00
**Researcher**: GPT-5
**Git Commit**: 07a15a223fc11e9945c6948586fbc50ae4c6aa77
**Branch**: main
**Repository**: bifrost

## Research Question

Use `ai/commands/1_research_codebase.md` to perform codebase research for `ai/thoughts/tickets/eng-first-class-yaml-skill-prompts.md`.

## Summary

The current worktree contains spike support for a top-level YAML skill `prompt` field. `YamlSkillManifest` parses and trims it, `YamlSkillDefinition` exposes it, and `DefaultMissionExecutionEngine` prepends it to the single-shot mission system prompt before Bifrost's default execution instruction. Existing description-driven capability registration and tool descriptors still use `description`, not `prompt`.

The prompt spike is currently single-shot-centered. Planning uses `DefaultPlanningService.buildPlanningPrompt(...)` and visible tool descriptions; it does not read `YamlSkillDefinition.prompt()`. The step-loop path builds prompts through `StepPromptBuilder.buildStepPrompt(...)`, passing mission input, visible tools, and output schema, but not the skill prompt. Step model trace payloads record the step prompt they send. A follow-up design review changed the desired behavior so planning should receive the skill prompt when present, composed before Bifrost's planning contract.

The current trace payloads for model calls record `system`, `user`, attachment descriptors, and attachment counts where applicable. They do not currently include dedicated `skillPromptPresent`, `skillPrompt`, or `promptComposition` fields. Advisor request mutations are recorded through advisor trace facts, while advisor implementations append schema, linter, or evidence hints to the current system prompt.

The spike also adds Bifrost-native nullable output schema support. `OutputSchemaManifest` now has `nullable`, `OutputSchemaValidator` accepts JSON null for nullable fields, and `OutputSchemaPromptAugmentor` describes nullable fields as `<type> or null`. The step-loop final response schema example currently renders scalar examples by type and does not account for `nullable`.

## Decision Update: Planning Should Receive Skill Prompt

After the initial research and plan draft, the planning prompt decision was revisited before implementation. The updated desired semantics are:

- `prompt` is model-facing skill instruction text for all model calls made on behalf of the YAML skill.
- For `planning_mode: true`, planning model calls should include `prompt` when present.
- The skill prompt should be composed before Bifrost's planning prompt, while Bifrost's planner-specific plan JSON contract, retry feedback, and output requirements remain appended after it.
- `description` remains the concise capability/tool-selection text. Including `prompt` in the planning system prompt does not mean using `prompt` as a tool description or catalog summary.
- Usage accounting should include prompt text for planning calls once planning receives the composed system prompt.
- Tests should verify that planning receives the skill prompt, that planner contract instructions follow it, and that visible tool descriptions still come from `description`.

The codebase findings below still describe the current spike state accurately: `DefaultPlanningService` does not yet receive or compose `YamlSkillDefinition.prompt()`.

The feedstock pure YAML sample now declares `prompt`, an image attachment input, nullable output fields, a JSON-only regex linter, and `model: openai-vision`; sample configuration maps `openai-vision` to OpenAI `gpt-5-mini`.

## Detailed Findings

### YAML Manifest and Catalog

`YamlSkillManifest` has a top-level `prompt` field next to `name`, `description`, and `model` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:16`). Its setter stores `prompt.strip()` when the text is nonblank and stores `null` for blank or whitespace-only input (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:82`).

The manifest class is strict about unknown YAML fields through `@JsonIgnoreProperties(ignoreUnknown = false)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:13`). The YAML object mapper is also configured with `FAIL_ON_UNKNOWN_PROPERTIES` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:733`).

`YamlSkillCatalog.loadDefinition(...)` validates required `name`, `description`, and `model`, then validates input schema, output schema, evidence contract, and linter before resolving model configuration (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:139`). There is currently no catalog validation branch that checks `prompt` against `mapping.target_id`.

`YamlSkillDefinition.prompt()` returns `manifest.getPrompt()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:49`). Mapping remains exposed separately through `mappingTargetId()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:79`).

### Description, Capability Metadata, and Tool Surfaces

`YamlSkillCapabilityRegistrar` registers YAML capabilities with `definition.manifest().getDescription()` as the capability description (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:47`). Its tool descriptors also use the manifest description for declared input schemas, generic descriptors, and mapped target descriptors (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:86`).

`DefaultToolCallbackFactory` turns capability metadata into Spring AI tool callbacks and sets the tool callback description from `capability.tool().description()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:81`). This connects YAML `description` to the model-visible tool surface.

The planner's visible tool list uses each `ToolCallback`'s `ToolDefinition.description()` via `DefaultPlanningService.describeTool(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:625`). `PlanQualityValidator.ToolSummary.from(...)` likewise summarizes tools from tool definition name plus description (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/PlanQualityValidator.java:243`).

### Single-Shot Execution Prompt Composition

`DefaultMissionExecutionEngine.executeMission(...)` materializes mission input, optionally initializes a plan, then builds an execution system prompt either without a plan or from the current stored plan (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:135`).

The no-plan prompt builder uses `Execute the mission using only the visible YAML tools when needed.` as the default system prompt. If `definition.prompt()` is nonblank, it returns the skill prompt, two newlines, and then the default prompt (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:414`).

The plan-aware single-shot prompt builder creates a plan summary with ready tasks, blocked tasks, and active task. It also prepends `definition.prompt()` when present (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:425`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:455`).

The single-shot model request is sent through `MissionUserMessageSender.send(...)` with the composed execution prompt (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:176`). Usage extraction receives the same `executionPrompt`, so heuristic prompt accounting uses the composed prompt text (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:199`).

### Planning

`DefaultPlanningService.requestPlanAttempt(...)` builds `planningPrompt` with `buildPlanningPrompt(capabilityName, visibleTools, retryFeedback, evidenceContract)` and builds the user message with `MissionInputMessageFormatter.buildUserMessage(objective, missionInput)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:415`).

The planning prompt construction takes capability name, visible tool callbacks, retry feedback, and evidence contract. It does not receive or read `YamlSkillDefinition.prompt()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:512`).

Planning trace payloads and sent payloads contain `system` and `user` only (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:426`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:435`). The Spring AI call sets the planning-call advisor context key so output schema and linter advisors can bypass planning calls (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:438`).

Planning usage accounting records `attemptResult.prompt()`, which is the planning prompt built by `DefaultPlanningService`, not the skill prompt (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:312`).

### Step-Loop Execution

`StepLoopMissionExecutionEngine.executeMission(...)` materializes mission input, initializes a plan when planning is enabled, requires a stored plan, and then enters `executeStepLoop(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:242`).

Each step builds its system prompt through `StepPromptBuilder.buildStepPrompt(...)`. The arguments include the plan, objective, trace-safe mission input, step number, prior result/summary, visible tools, final-response mode, verbose tool-argument guidance flag, and output schema. The current call does not pass `YamlSkillDefinition.prompt()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:430`).

The step user message is built separately through `StepPromptBuilder.buildStepUserMessage(...)` with plan, objective, and trace-safe mission input (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:441`).

`callModelForStep(...)` records model request prepared and sent payloads with `system`, `user`, `attachments`, and `attachmentCount`; it sends the prompt through `MissionUserMessageSender.send(...)`; and it records usage with the same `stepPrompt` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:576`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:591`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:615`).

`StepPromptBuilder` appends final-response output schema guidance only when `finalResponseOnly` is true (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:137`). Its schema example renderer returns scalar placeholders such as `"<string>"`, `<number>`, and `<boolean>` by type and currently does not branch on `schema.getNullable()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:279`).

### Advisors and Prompt Augmentation

`OutputSchemaCallAdvisor` skips planning calls when `OutputSchemaCallAdvisor.PLANNING_CALL_KEY` is true (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java:78`). For non-planning calls, it mutates the current prompt by applying `OutputSchemaPromptAugmentor.augment(...)` before making the downstream model call (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java:83`).

`OutputSchemaPromptAugmentor` appends JSON-only and output schema summary guidance to the existing system message using `joinSystemText(original, guidance)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaPromptAugmentor.java:14`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaPromptAugmentor.java:70`).

`LinterCallAdvisor` also skips planning calls via the same planning-call key and appends linter retry hints to the current system prompt on retry (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java:77`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java:149`).

`EvidenceContractCallAdvisor` validates final response evidence coverage against produced evidence and appends evidence retry hints to the current system prompt on retry (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/evidence/EvidenceContractCallAdvisor.java:56`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/evidence/EvidenceContractCallAdvisor.java:99`).

Advisor trace recording is wired in `DefaultSkillAdvisorResolver`: advisor facts become `ADVISOR_REQUEST_MUTATION_RECORDED` or `ADVISOR_RESPONSE_MUTATION_RECORDED` records on the active frame (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:59`).

### Trace and Journal Behavior

`DefaultExecutionStateService.traceModelCall(...)` records `MODEL_REQUEST_PREPARED`, lets the callback record `MODEL_REQUEST_SENT`, and records `MODEL_RESPONSE_RECEIVED` with the response payload (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:235`).

Single-shot prepared payloads currently include `system`, `user`, `attachments`, and `attachmentCount` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:165`). Single-shot sent payloads add tool callback count and tool names (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:363`).

Step-loop prepared and sent payloads contain `system`, `user`, `attachments`, and `attachmentCount` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:576`).

No current production or test code references `skillPrompt`, `skillPromptPresent`, or `promptComposition` fields. The current trace has model request payload `system` text and separate advisor mutation records.

`BifrostSession` derives its execution journal from the canonical trace through `ExecutionJournalProjector` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:372`). There is no dedicated prompt-specific journal field or event type in `TraceRecordType` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TraceRecordType.java:1`).

### Logging

The prompt field itself is not logged by manifest or execution code in normal production paths found during this research. Existing planning debug logs preview planning response payloads, not prompts (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:449`). Output schema validation logs summarize validation failures and do not log full prompt text (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java:113`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java:134`).

### Nullable Output Schema Support

`YamlSkillManifest.OutputSchemaManifest` now carries a `nullable` Boolean with getter and setter (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:455`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:545`).

`OutputSchemaValidator.validateNode(...)` returns early when the response node is JSON null and the schema property has `nullable: true` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaValidator.java:59`).

`OutputSchemaPromptAugmentor.describeType(...)` appends `or null` when `schema.getNullable()` is true (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaPromptAugmentor.java:42`).

`OutputSchemaCallAdvisorTest.acceptsNullableSchemaFieldsAndMentionsNullabilityInPromptGuidance(...)` covers a nullable string field accepting `null` and the prompt guidance containing `string or null` (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java:128`).

### Feedstock Sample

`feedstock_ticket_parser_by_skill.yml` defines `feedstockTicketParserBySkill`, uses `model: openai-vision`, disables planning, and declares a top-level prompt with feedstock weighmaster parsing instructions (`bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml:1`, `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml:5`, `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml:7`).

The sample declares an `image` input as `type: attachment`, `media_type: image`, and `allowed_content_types: image/jpeg` (`bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml:35`).

Each output property in the feedstock sample currently declares `nullable: true` and field-level descriptions; all fields are also listed as required top-level fields (`bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml:48`, `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml:101`).

The sample linter requires raw JSON object output with a regex pattern and message (`bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml:117`).

`bifrost-sample/src/main/resources/application.yml` maps `openai-vision` to provider `openai` and provider model `gpt-5-mini` (`bifrost-sample/src/main/resources/application.yml:51`).

### Tests Covering the Current Spike

`MissionExecutionEngineTest.prependsSkillPromptToSingleShotExecutionPrompt(...)` verifies that a skill prompt starts the single-shot system message and that the default execution instruction remains present (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java:108`).

`MissionExecutionEngineTest.recordsFullMissionRequestPayloadWhenRequestIsSent(...)` verifies the single-shot sent trace payload's `system`, `user`, tool callback count, and tool names, but it currently expects the default system prompt and does not assert prompt metadata fields (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java:134`).

`YamlSkillCatalogTests` contains coverage for attachment input schema and output-schema attachment rejection, but this test file currently has no prompt-specific tests (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:178`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:193`).

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:19` - top-level `prompt` field.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:87` - blank prompts are normalized to `null`; nonblank prompts are stripped.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:49` - definition accessor for `prompt`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:47` - capability metadata uses YAML `description`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:414` - single-shot execution prompt prepends skill prompt.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:455` - plan-aware single-shot prompt also prepends skill prompt.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:512` - planning prompt builder has no skill prompt parameter.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:430` - step-loop prompt builder call passes output schema but not skill prompt.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:279` - step-loop schema examples render scalar placeholders by type.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaPromptAugmentor.java:42` - nullable output schema guidance says `or null`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaValidator.java:59` - nullable fields accept JSON null.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java:235` - model request tracing records prepared/sent/response payloads.
- `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml:7` - feedstock pure YAML skill prompt.
- `bifrost-sample/src/main/resources/application.yml:51` - `openai-vision` sample model mapping.

## Architecture Documentation

YAML manifest loading is strict and typed. Manifest fields are parsed into `YamlSkillManifest`, then `YamlSkillCatalog` validates required fields and schema blocks before producing `YamlSkillDefinition`. The `prompt` field currently lives at the manifest/definition layer but has little catalog-level behavior beyond parsing and blank normalization.

Capability discovery and model execution are deliberately separate. Capability metadata and tool descriptors are built from `description`, which flows through `CapabilityMetadata`, `CapabilityToolDescriptor`, `ToolCallback`, planning visible tool lists, and plan quality validation. The current `prompt` field does not enter those planner/tool surfaces.

Single-shot execution composes a base system prompt inside `DefaultMissionExecutionEngine`, records that base prompt in trace payloads, sends it through `MissionUserMessageSender`, and records usage using the same prompt string. Advisors then mutate Spring AI requests around the model call by appending output schema, linter, or evidence guidance to whatever system prompt the engine supplied.

Planning currently uses an independent planning prompt. It is built in `DefaultPlanningService`, describes the plan JSON contract and visible sub-skills, and marks the call as planning via advisor context. Planning usage and trace payloads currently reflect this planning prompt rather than a skill-level execution prompt. The updated desired behavior is to prepend the skill prompt to this planning prompt while keeping Bifrost's planning contract appended after it.

Step-loop execution uses a separate step prompt builder and a runtime action contract. The model receives a fresh prompt per step with current plan status, ready/waiting/blocked/completed tasks, visible tools, recent execution context, and final-response schema guidance when completing. Current step prompts do not include the skill prompt.

Execution trace is the canonical runtime record. Journal entries are projected from trace records, and there is no separate runtime journal append path for prompt configuration. Model-call payloads store prompt text in the `system` field and advisor changes in advisor mutation records.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/tickets/eng-first-class-yaml-skill-prompts.md` - Defines the prompt-vs-description semantics, mapped-skill rejection decision, updated planning/step-loop desired behavior, trace metadata decision, logging policy, usage expectations, nullable output schema scope, and feedstock sample target.
- `ai/thoughts/tickets/eng-first-class-attachments-for-yaml-skills.md` - Introduces the pure YAML feedstock parser motivation, attachment input shape, descriptor-only planning policy, trace redaction policy, and notes that nullable output schema was separate from first-class attachment support.
- `ai/thoughts/research/2026-05-17-first-class-attachments-yaml-skills.md` - Documents the pre-attachment state where YAML execution rendered mission input as text and the feedstock parser was Java-backed; follow-up sections record decisions about attachment schema, descriptor-only traces, and descriptor-only planning.
- `ai/thoughts/plans/2026-05-18-first-class-attachments-yaml-skills.md` - Records the attachment implementation plan that split input and output schema manifests, added attachment materialization, routed attachments through single-shot and step-loop execution, and added the pure YAML feedstock sample.
- `ai/thoughts/phases/phase6.md` - Documents the plan-step loop architecture: `planning_mode: true` uses step-loop execution, planning creates a plan once, and each step prompt carries bounded runtime-owned context.
- `ai/thoughts/phases/README.md` - Describes Bifrost's YAML skill architecture, including manifest metadata, mapping targets, model selection, planning mode, and the current separation between YAML skill contracts and Java implementations.

## Related Research

- `ai/thoughts/research/2026-05-17-first-class-attachments-yaml-skills.md` - Related research for attachment support that immediately precedes this prompt feature.

## Open Questions

The codebase currently shows no implemented catalog validation for `prompt` combined with `mapping.target_id`.

The codebase currently shows no dedicated trace metadata fields for `skillPromptPresent`, `skillPrompt`, or `promptComposition`; prompt text is visible only as part of the `system` payload when included in the base prompt.

The codebase currently shows no planning composition path that includes `YamlSkillDefinition.prompt()` in planning model calls.

The codebase currently shows no step-loop composition path that includes `YamlSkillDefinition.prompt()` in step execution or final-response prompts.

The codebase currently shows no step-loop final-response schema example branch that represents nullable fields as nullable examples or guidance.

The codebase currently shows no prompt-specific logging tests or trace tests beyond single-shot system prompt inclusion.
