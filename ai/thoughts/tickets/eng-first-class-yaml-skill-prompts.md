# Ticket: First-Class Prompt Instructions for YAML Skills

## Summary

Add a deliberate, first-class `prompt` field to YAML skill manifests so skill authors can define model-facing skill instructions at the skill level, separate from user-facing/capability-facing `description` metadata.

This is needed for pure YAML skills that require detailed behavioral guidance. The immediate motivating example is the feedstock ticket image parser:

- Existing Java-backed implementation:
  - `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java`
  - `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser.yml`
- Pure YAML skill target:
  - `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml`

The pure YAML version needs the same detailed extraction instructions as the Java service. Field descriptions and `description` alone are not enough.

## Context

During work on first-class attachments for YAML skills, the sample app gained enough runtime support for an image attachment input to be passed to an LLM-backed YAML skill. The next gap was prompt quality: the existing `feedstock_ticket_parser_by_skill.yml` had the attachment input shape and output fields, but lacked most of the prompt/schema guidance from `FeedstockFormExtractionService`.

A quick spike added a manifest-level `prompt:` field and wired it into single-shot execution. This proved the feature is useful, but it was intentionally minimal and should not be treated as complete without a design pass.

The user explicitly raised the concern that `prompt` was "thrown in" without discussing impacts. This ticket should turn that spike into an intentional Bifrost feature.

## Current Spike State

At the time this ticket was written, the working tree contains spike changes that may or may not be kept as-is:

- `YamlSkillManifest`
  - Added top-level `prompt`.
  - Added `nullable` to `OutputSchemaManifest`.
- `YamlSkillDefinition`
  - Added `prompt()` accessor.
- `DefaultMissionExecutionEngine`
  - Prepends `definition.prompt()` to the single-shot execution system prompt.
- `OutputSchemaValidator`
  - Accepts `null` when an output schema property has `nullable: true`.
- `OutputSchemaPromptAugmentor`
  - Describes nullable fields as `<type> or null`.
- `bifrost-sample/src/main/resources/application.yml`
  - Changed `openai-vision.provider-model` from `gpt-4.1-mini` to `gpt-5-mini`.
- `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml`
  - Added `prompt:` with feedstock extraction rules copied/adapted from the Java service.
  - Added field-level output schema descriptions.
  - Added `nullable: true` to feedstock output fields.
  - Added a JSON-only regex linter.
- Tests added/changed:
  - `MissionExecutionEngineTest` has coverage that a skill prompt reaches single-shot execution.
  - `OutputSchemaCallAdvisorTest` has coverage for nullable output schema fields.

Verification run during the spike:

```powershell
mvn -pl bifrost-spring-boot-starter clean "-Dtest=MissionExecutionEngineTest,OutputSchemaCallAdvisorTest,YamlSkillCatalogTests" test
mvn -pl bifrost-sample -am test
```

Both passed after the spike fixes. A direct `mvn -pl bifrost-sample test` failed before using `-am` because Maven tested the sample against a previously built starter artifact that did not know the new `prompt` field.

## Problem

YAML skills currently have no clearly designed place for long-form model-facing instructions.

Today:

- `description` is required and used as capability metadata.
- `description` becomes part of `CapabilityMetadata`.
- `description` is used as the tool description visible to planners/agents.
- `description` should stay concise so tool selection remains clean.
- Detailed operational instructions placed in `description` would pollute planner/tool surfaces and make catalog metadata noisy.
- Output schema descriptions help with field semantics, but are not enough for task-level rules such as "verify Gross - Tare = Net" or "return null instead of guessing."

The spike introduced `prompt`, but only single-shot execution uses it. That leaves ambiguity and incomplete behavior around:

- How `prompt` differs from `description`.
- Whether planners should see `prompt`.
- How `prompt` affects `planning_mode: true`.
- How prompt text appears in traces and journals.
- How prompt text composes with output schema/linter/evidence advisors.
- Whether mapped YAML skills should allow or ignore `prompt`.
- How to test and document the feature.

## Desired Semantics

### `description`

`description` should remain short metadata that explains what the skill is and when it should be selected.

Use it for:

- Capability registry display.
- Tool descriptions.
- Planner/tool selection.
- Human-readable skill catalogs.
- Short summaries in traces or debug logs.

It should not contain long parsing instructions, formatting rules, validation heuristics, or task procedure.

Example:

```yaml
description: Parses a feedstock weighmaster certificate image into structured ticket fields.
```

### `prompt`

`prompt` should be model-facing skill instruction text.

Use it for:

- Task behavior.
- Domain-specific extraction rules.
- Validation logic.
- Style/format constraints not already covered by output schema/linter.
- Guidance about uncertainty, nulls, confidence, or field interpretation.

Example:

```yaml
prompt: |
  Act as a high-precision document parser for logistics weighmaster certificates.
  Verify Gross - Tare = Net.
  If a field is missing, illegible, or low confidence, return null instead of guessing.
```

## Design Questions to Resolve

The following design questions have been discussed and resolved. The implementation plan should preserve these decisions unless new code research reveals a concrete blocker.

### 1. Should `prompt` be optional?

Decision: yes. `prompt` is optional.

Existing YAML skills should continue working with only `description`.

### 2. Should `prompt` be allowed on mapped YAML skills?

Mapped YAML skills delegate execution to a Java target via `mapping.target_id`; their model prompt is not used directly by the YAML execution engine.

Decision: reject `prompt` when `mapping.target_id` is present.

It should be a configuration error for a delegated/mapped YAML skill to declare `prompt`, because the YAML prompt would not be used. Silent ignore is confusing. If a mapped skill needs prompt behavior, the Java target owns that prompt.

The existing Java-backed feedstock skill should not need `prompt`.

### 3. Should planners see `prompt`?

Decision: yes.

`prompt` is model-facing skill instruction text. When `planning_mode: true`, the planning model is also acting on behalf of the YAML skill, so authors should expect the skill prompt to influence decomposition and task ordering. If a developer declares both `planning_mode: true` and `prompt`, the intuitive behavior is that the prompt applies to the planning call as well as step execution and final response generation.

Planning should compose the skill prompt before Bifrost's planning contract. Bifrost's planner-specific instructions remain appended after the skill prompt so the plan JSON contract and retry feedback remain the immediate, authoritative output instructions for the planning call.

Example composition:

```text
<skill prompt>

You are planning execution for this YAML skill.
Use the skill instructions above to decide the right plan.
Return ONLY a valid Bifrost execution plan JSON...
```

Documentation should state that `prompt` must be compatible with all model calls for the skill, including planning when `planning_mode: true`. This is the same kind of responsibility authors already have for prompts used with execution, output schema, linter, and evidence constraints.

### 4. How should `prompt` affect `planning_mode: false`?

Decision: compose `prompt` into the mission execution system prompt before built-in Bifrost execution instructions.

Current spike behavior:

```text
<skill prompt>

Execute the mission using only the visible YAML tools when needed.
```

This ordering is intentional: skill-specific execution behavior comes first, then Bifrost's generic execution guardrails.

### 5. How should `prompt` affect `planning_mode: true` / step-loop execution?

Current spike behavior: it does not affect step-loop execution. This is incomplete.

Decision:

- Planning model calls: include `prompt` when present, composed before Bifrost's planning instructions.
- Step execution model calls: include `prompt` in every step system prompt, because step execution is the model actually carrying out the skill.
- Final response generation in step-loop: include `prompt`, because final output must obey skill-level behavior.

The implementation should update `DefaultPlanningService`, `StepLoopMissionExecutionEngine`, and/or prompt builders in a way that avoids duplicating prompt composition logic.

### 6. How should `prompt` compose with advisors?

Existing advisors mutate system prompts:

- `OutputSchemaCallAdvisor`
- `LinterCallAdvisor`
- `EvidenceContractCallAdvisor`

Decision:

- Base skill prompt should be part of the base system prompt before advisor augmentation.
- Advisor constraints should remain appended after the base prompt so schema/linter/evidence corrections remain visible and authoritative.
- Tests should verify prompt text survives advisor augmentation.

### 7. How should `prompt` appear in traces and journals?

Current spike behavior:

- Single-shot model trace payload records the composed `system` field, which includes `prompt`.
- `MODEL_REQUEST_PREPARED` and `MODEL_REQUEST_SENT` contain the pre-advisor system prompt.
- Advisor request mutations are recorded separately.
- There is no explicit `skillPrompt` trace field.
- There is no dedicated journal event for prompt configuration.

Decision:

- Trace model request payloads should include:
  - `system`: the effective base system prompt before advisor mutation, as today.
  - `skillPromptPresent`: boolean.
  - `skillPrompt`: exact prompt text.
  - `promptComposition`: a short descriptor such as `skill_prompt_plus_default_execution_prompt`.
- Consider whether traces should capture the effective system prompt after advisor mutation. If not feasible with Spring AI advisors, document that advisor mutations are recorded separately.
- Journal output likely does not need a user-facing prompt event unless journals are intended to explain model behavior. Avoid noisy journal entries unless there is a clear UX need.

### 8. Should `prompt` be logged?

Decision:

- Do not log full prompt text at normal log levels.
- Debug logs can mention `promptPresent=true` and length/line count.
- Avoid accidentally logging long or sensitive prompt content.

### 9. How should `prompt` affect usage accounting?

Decision: usage accounting should include prompt text for model calls that receive it.

Usage estimation already receives the system prompt. Once prompt is composed into the system prompt, heuristic usage accounting should include it.

Verify:

- Single-shot usage includes prompt.
- Planning usage includes prompt when planning receives it.
- Step-loop usage includes prompt after step-loop support is added.

### 10. Should nullable output schema support stay in this ticket?

Nullable output fields were added during the same spike because the feedstock parser needs "return null instead of guessing" semantics.

Decision: keep nullable output schema support in this ticket as an adjacent, explicitly scoped companion change.

Rationale:

- The feedstock YAML skill needs nulls to avoid forcing the model to hallucinate uncertain fields.
- A quick code check shows this is an easy, localized change if Bifrost uses a Bifrost-native `nullable: true` schema marker instead of JSON Schema union types like `["string", "null"]`.
- The spike already showed the core change is small: add `nullable` to `OutputSchemaManifest`, let `OutputSchemaValidator` accept JSON `null` for nullable fields, and have `OutputSchemaPromptAugmentor` describe those fields as `<type> or null`.

Scope notes:

- Use `nullable: true` in YAML output schemas.
- Do not implement JSON Schema union types in this ticket.
- Keep nullable support output-schema-only unless a separate input-schema need is identified.
- Include step-loop final response schema examples/guidance so nullable fields are represented consistently there too.
- Document nullable as output schema support, not as prompt support.

## Impacted Areas

Likely files/classes to inspect and update:

- Manifest/catalog:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
- Single-shot execution:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/attachment/SpringAiMissionUserMessageSender.java`
- Planning:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/PlanQualityValidator.java`
- Step-loop execution:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java`
- Advisors:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaPromptAugmentor.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/evidence/EvidenceContractCallAdvisor.java`
- Tracing/journal:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
  - Trace readers/writers under `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/trace`
- Usage:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/usage/ModelUsageExtractor.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/usage/DefaultSessionUsageService.java`
- Sample:
  - `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml`
  - `bifrost-sample/src/main/resources/application.yml`
  - `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java`

## Acceptance Criteria

- YAML skills may declare an optional top-level `prompt` field.
- Existing skills without `prompt` continue to load and execute unchanged.
- `description` remains the public/tool/planner summary and is not replaced by `prompt`.
- YAML skills with `mapping.target_id` must fail catalog validation if they also declare `prompt`.
- `prompt` is sent to the execution model for `planning_mode: false` skills.
- `prompt` is sent to the planning model for `planning_mode: true` skills.
- `prompt` is sent to step execution/final response model calls for `planning_mode: true` skills, if step-loop execution is enabled.
- Bifrost's planning contract is appended after the skill prompt so planner output format requirements remain authoritative.
- Traces clearly show whether a skill prompt was present and how it was composed into the model request.
- Trace request payloads include exact `skillPrompt` text when a prompt is present.
- Prompt text is not accidentally logged at normal log levels.
- Usage accounting includes prompt text for model calls that receive it.
- Advisor-added constraints still compose correctly with skill prompts.
- Output schemas support `nullable: true` for fields where JSON `null` is an acceptable model response.
- Nullable output fields are described as nullable in single-shot and step-loop schema guidance.
- The feedstock pure YAML skill contains:
  - attachment image input
  - `prompt` with the Java parser's extraction guidance
  - field-level output schema descriptions
  - null semantics for uncertain/missing fields
  - `model: openai-vision`, with `openai-vision` targeting `gpt-5-mini`
- Tests cover single-shot execution, step-loop execution, planner behavior, trace/journal behavior, advisor composition, and sample skill registration.

## Suggested Tests

### Manifest/catalog tests

- Loads a skill with `prompt`.
- Trims blank/whitespace-only prompt to null or rejects it; choose behavior deliberately.
- Rejects unknown prompt-like fields if strict manifest validation remains enabled.
- Rejects `prompt` on mapped YAML skills with `mapping.target_id`.
- Loads output schema fields with `nullable: true`.
- Rejects `nullable` in input schemas unless a separate input-schema nullable design is added.

### Single-shot execution tests

- Skill prompt is included in the system prompt sent to `ChatClient`.
- Existing skills without prompt keep the old default system prompt exactly or semantically unchanged.
- Trace `MODEL_REQUEST_PREPARED` and `MODEL_REQUEST_SENT` include prompt-related metadata.
- Usage estimation receives the composed system prompt.

### Step-loop tests

- Skill prompt is included in step model calls.
- Skill prompt is included in final response calls.
- Skill prompt is not duplicated across retries.
- Planning call includes skill prompt before Bifrost's planning contract.
- Nullable output schema examples/guidance in step-loop prompts allow `null` where configured.

### Planner tests

- Planner still uses `description` for tool selection.
- Planner prompt includes `prompt` when present.
- Planner-specific JSON output instructions appear after the skill prompt.
- Planning usage estimation receives the composed planning system prompt.
- Tool descriptions remain concise and do not include full execution prompt.

### Advisor composition tests

- Output schema guidance is appended to a system prompt that already contains skill prompt.
- Linter retry/correction prompts preserve the skill prompt.
- Evidence contract correction prompts preserve the skill prompt.

### Trace/logging tests

- Trace contains prompt-present metadata.
- Trace contains exact `skillPrompt` text when configured.
- Trace does not include raw attachments/base64 when prompt is used with attachment skills.
- Normal logs mention only prompt presence/length, not full prompt text.

### Sample tests

- Sample app context starts with `feedstockTicketParserBySkill`.
- `openai-vision` resolves to provider model `gpt-5-mini`.
- `feedstockTicketParserBySkill` has an image attachment input and a nonblank prompt.
- `feedstockTicketParserBySkill` output fields that may be uncertain use `nullable: true`.

## Feedstock Prompt Source

The prompt in the pure YAML sample should be based on the Java service prompt:

```java
Act as a high-precision document parser for logistics weighmaster certificates.

CRITICAL GUIDELINES:

Handwriting vs. Print: Fields in the 'Parties' section are handwritten in ink. Use context to decode cursive.

### EXTRACTION GOALS:
1. IDENTIFY THE TAG: Search the entire document for a white sticker containing a 'ZHCBZ'+ number. It should be 11 digits total. This is the primary tracking ID.
2. RED TICKET ID: Extract the red printed number in the top right.
3. SCALE STAMPS: Extract Gross, Tare, and Net weights from the dot-matrix scale stamps on the right.
4. HANDWRITTEN DATA: Transcribe the ink pen for parties and totals.

### LOGIC & VALIDATION:
- MATH CHECK: You MUST verify: Gross - Tare = Net. If they do not match, re-examine the scale stamps to find the most visually plausible digits that satisfy the equation.
- DO NOT GUESS: If a field is missing, illegible, or you are not confident, return null for that field. Never invent values to satisfy the schema. Returning null is strongly preferred over a guess.
- DATE/TIME FORMAT: Return datetime_in and datetime_out as local datetimes in ISO-8601 format: yyyy-MM-dd'T'HH:mm:ss. If the stamp has a two-digit year, infer 20xx.
```

Do not blindly copy markdown heading syntax if the YAML style prefers plain headings. Preserve the behavioral content.

## Related Work

- Attachment support ticket:
  - `ai/thoughts/tickets/eng-first-class-attachments-for-yaml-skills.md`
- Relevant sample files:
  - `bifrost-sample/src/main/resources/forms/feedstock-p1.jpg`
  - `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser.yml`
  - `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml`
  - `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java`

## Recommended Process

Do not treat this as a one-off patch. Run it through the repo's comprehensive process:

1. `ai/commands/1_research_codebase.md`
2. `ai/commands/2_create_plan.md`
3. `ai/commands/3_testing_plan.md`
4. `ai/commands/4_implement_plan.md`
5. `ai/commands/5_code_review.md`

This is a manifest/runtime feature that crosses several seams. The spike is useful context, but the final implementation should come from a reviewed plan.
