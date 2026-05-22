# First-Class Prompt Instructions for YAML Skills Implementation Plan

## Overview

Add a deliberate, first-class `prompt` field to YAML skill manifests so skill authors can keep concise capability metadata in `description` while placing model-facing execution instructions in `prompt`.

This plan turns the current spike into an intentional Bifrost feature across catalog validation, single-shot execution, step-loop execution, traces, usage accounting, nullable output schema support, and the feedstock pure YAML sample.

## Current State Analysis

The worktree already contains useful spike changes. `YamlSkillManifest` parses a top-level `prompt` and normalizes blank text to `null`; `YamlSkillDefinition` exposes it; `DefaultMissionExecutionEngine` prepends it to single-shot execution prompts; and nullable output schema support exists in the output schema manifest, validator, and advisor prompt guidance.

The remaining gaps are product semantics and runtime consistency. Catalog loading does not reject `prompt` on mapped YAML skills, planning and step-loop execution do not include the skill prompt in their model prompts, trace payloads do not carry dedicated prompt metadata, and step-loop final-response schema examples do not represent nullable fields. Planner/tool selection surfaces still use `description`, which is correct and should be protected with tests.

## Desired End State

YAML skills may declare an optional top-level `prompt` for model-facing skill instructions. Existing skills without `prompt` continue to load and execute unchanged. `description` remains the planner/tool/capability summary and does not absorb long behavioral guidance.

For unmapped model-backed YAML skills, Bifrost composes the skill prompt into every model call made on behalf of that YAML skill, including planning when `planning_mode: true`, step execution, and final response generation. The skill prompt appears before Bifrost's call-specific instructions so skill-specific behavior informs the call while Bifrost's planner, execution, output schema, linter, and evidence contracts remain appended after it. Mapped YAML skills that delegate through `mapping.target_id` fail catalog validation if they declare `prompt`, because that prompt would not be consumed by YAML model execution.

Trace request payloads expose prompt metadata explicitly:

```json
{
  "skillPromptPresent": true,
  "skillPrompt": "Act as a high-precision document parser...",
  "promptComposition": "skill_prompt_plus_default_execution_prompt"
}
```

Usage accounting includes prompt text for model calls that receive it. Normal logs do not emit full prompt text. Nullable output schema support remains in scope as an output-schema-only companion feature using Bifrost-native `nullable: true`.

### Key Discoveries

- `YamlSkillManifest` currently has top-level `prompt` next to `name`, `description`, and `model`, with blank normalization in `setPrompt(...)` at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:19` and `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:87`.
- `YamlSkillDefinition.prompt()` exposes the manifest prompt at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:49`.
- Catalog loading validates required fields and schemas but does not currently validate `prompt` against `mapping.target_id` at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:139`.
- Capability metadata and tool descriptors still use `description`, not `prompt`, through `YamlSkillCapabilityRegistrar` at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:47`.
- Single-shot execution prepends `definition.prompt()` in `buildExecutionPrompt(...)` at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:414` and `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:425`.
- Step-loop execution builds prompts through `StepPromptBuilder.buildStepPrompt(...)` without passing `definition.prompt()` at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:430`.
- Step-loop model trace payloads currently include `system`, `user`, `attachments`, and `attachmentCount`, but not dedicated prompt metadata, at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:576`.
- Planning currently builds an independent planning prompt and usage record, and does not read `YamlSkillDefinition.prompt()`, at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:512`.
- Advisor prompt augmentation appends schema, linter, and evidence constraints to the current system prompt, preserving earlier base prompt text, at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaPromptAugmentor.java:14`.
- Nullable output fields are already accepted by `OutputSchemaValidator` and described as `or null` by `OutputSchemaPromptAugmentor` at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaValidator.java:59` and `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaPromptAugmentor.java:42`.
- Step-loop final-response schema examples still render scalar placeholders by type without nullable awareness at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:279`.
- The feedstock pure YAML skill already declares a prompt, image attachment input, nullable output fields, and `model: openai-vision` at `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml:7`.

## What We're NOT Doing

- Do not introduce a separate planner-facing `planning_prompt`, `planner_hint`, or similar field in this ticket.
- Do not allow `prompt` on mapped/delegated YAML skills.
- Do not move long execution guidance into `description`.
- Do not implement JSON Schema union types such as `["string", "null"]`.
- Do not add nullable support to input schemas unless a separate input-schema design is created.
- Do not add prompt-specific user-facing journal events unless a concrete UX need appears.
- Do not log full prompt text at normal log levels.
- Do not replace the existing Java-backed feedstock parser sample.

## Implementation Approach

Keep the manifest field and nullable support from the spike, but make prompt composition explicit and reusable. Add a small runtime value/helper for composed model-call prompts so single-shot, planning, and step-loop code do not duplicate prompt ordering or trace metadata. Validate mapped skills at catalog load time. Pass skill prompt into planning, step execution, and final-response prompts for `planning_mode: true`. Extend trace payload builders to include prompt metadata beside the existing `system` field.

Before implementation, run `ai/commands/3_testing_plan.md` for this plan to create the detailed failing-test-first artifact and exact test commands.

## Phase 1: Manifest and Catalog Semantics

### Overview

Finalize manifest-level behavior: `prompt` is optional, blank prompts normalize to absent, unknown prompt-like fields remain rejected by strict manifest parsing, and mapped YAML skills cannot declare `prompt`.

### Changes Required

#### 1. Preserve prompt parsing behavior

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`

**Changes**:
- Keep `prompt` as a top-level manifest field.
- Keep blank or whitespace-only prompt normalization to `null`.
- Do not add aliases such as `instruction`, `instructions`, or `system_prompt`.
- Keep strict unknown-property behavior.

#### 2. Reject prompt on mapped YAML skills

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`

**Changes**:
- Add validation after required-field checks and before returning `YamlSkillDefinition`.
- If `manifest.getPrompt()` has text and `manifest.getMapping().getTargetId()` has text, throw the existing `invalidSkill(...)` configuration error.
- Use a field path such as `prompt` and an error message that explains the prompt is unused for mapped skills.

```java
private void validatePrompt(Resource resource, YamlSkillManifest manifest) {
    if (StringUtils.hasText(manifest.getPrompt())
            && manifest.getMapping() != null
            && StringUtils.hasText(manifest.getMapping().getTargetId())) {
        throw invalidSkill(resource, "prompt",
                "cannot be declared when mapping.target_id is present because mapped YAML skills delegate execution");
    }
}
```

#### 3. Lock description-only capability surfaces

**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTest.java` if present, otherwise add coverage near existing registrar tests

**Changes**:
- No production behavior change expected.
- Add or update tests proving capability metadata and tool descriptions use `description` and do not include `prompt`.

### Success Criteria

#### Automated Verification

- [x] Skill with nonblank `prompt` loads successfully: `mvn -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] Skill with blank `prompt` loads with `definition.prompt() == null`.
- [x] Skill with unknown prompt-like field still fails strict manifest validation.
- [x] Skill with `prompt` and `mapping.target_id` fails catalog validation.
- [x] Capability metadata/tool descriptors use `description` and omit `prompt`.

#### Manual Verification

- [x] Review the mapped-skill validation error text and confirm it is clear to YAML skill authors.

**Implementation Note**: After this phase and automated verification pass, pause for human review of the final manifest semantics before changing execution prompt plumbing.

---

## Phase 2: Shared Prompt Composition and Single-Shot Traces

### Overview

Replace ad hoc single-shot prompt prepending with an explicit composition helper that also produces trace metadata. Keep existing single-shot behavior semantically unchanged.

### Changes Required

#### 1. Add prompt composition model/helper

**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/prompt/SkillPromptComposition.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/prompt/SkillPromptComposer.java`

**Changes**:
- Add a small immutable value containing:
  - `systemPrompt`
  - `skillPromptPresent`
  - `skillPrompt`
  - `promptComposition`
- Add composition methods for default execution prompt, plan-aware execution prompt, planning prompt, and step-loop base prompt.
- Ensure ordering is always skill prompt first, blank line, Bifrost call-specific prompt.
- Use descriptors:
  - `default_execution_prompt`
  - `skill_prompt_plus_default_execution_prompt`
  - `planned_execution_prompt`
  - `skill_prompt_plus_planned_execution_prompt`
  - `planning_prompt`
  - `skill_prompt_plus_planning_prompt`
  - `step_execution_prompt`
  - `skill_prompt_plus_step_execution_prompt`

```java
public record SkillPromptComposition(
        String systemPrompt,
        boolean skillPromptPresent,
        @Nullable String skillPrompt,
        String promptComposition) {

    public Map<String, Object> traceMetadata() {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("skillPromptPresent", skillPromptPresent);
        if (skillPromptPresent) {
            metadata.put("skillPrompt", skillPrompt);
        }
        metadata.put("promptComposition", promptComposition);
        return Map.copyOf(metadata);
    }
}
```

#### 2. Update single-shot prompt building

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`

**Changes**:
- Replace `buildExecutionPrompt(...)` string helpers with composer calls.
- Use `composition.systemPrompt()` for model calls and usage extraction.
- Add `composition.traceMetadata()` to both `MODEL_REQUEST_PREPARED` and `MODEL_REQUEST_SENT` payloads.
- Preserve existing attachment descriptors and tool callback metadata.

#### 3. Test advisor composition around skill prompts

**Files**:
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/linter/LinterCallAdvisorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/evidence/EvidenceContractCallAdvisorTest.java` if present

**Changes**:
- Add tests that start with a system prompt containing a skill prompt and verify advisor-added guidance is appended without losing that text.

### Success Criteria

#### Automated Verification

- [x] Single-shot execution still prepends skill prompt before default Bifrost execution instructions.
- [x] Existing no-prompt single-shot skills keep the prior default prompt text.
- [x] Single-shot prepared and sent trace payloads include `skillPromptPresent`, exact `skillPrompt` when present, and `promptComposition`.
- [x] Usage extraction receives the composed prompt including skill prompt.
- [x] Output schema and linter advisor tests prove prompt text survives advisor augmentation.
- [x] Starter targeted tests pass: `mvn -pl bifrost-spring-boot-starter -Dtest=MissionExecutionEngineTest,OutputSchemaCallAdvisorTest,LinterCallAdvisorTest test`

#### Manual Verification

- [x] Inspect one single-shot trace payload and confirm prompt metadata is clear and no attachment bytes/base64 are present through trace assertions.

**Implementation Note**: After this phase and automated verification pass, pause for trace payload review before wiring the step-loop path.

---

## Phase 3: Planning and Step-Loop Execution

### Overview

Add skill prompt support to planning, step execution, and final-response model calls. Planning should see the skill prompt first, then Bifrost's planning contract, so skill instructions can shape decomposition while the planner JSON contract remains authoritative.

### Changes Required

#### 1. Thread skill prompt into step prompt composition

**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/prompt/SkillPromptComposer.java`

**Changes**:
- Keep `StepPromptBuilder` responsible for the Bifrost step-loop prompt body.
- Use `SkillPromptComposer` at the execution engine boundary to compose `skillDefinition.prompt()` around the builder output.
- Include skill prompt in every step execution call and in final-response-only calls.
- Ensure invalid-action retry feedback appends after the composed system prompt without duplicating the skill prompt.

```java
String stepPromptBody = StepPromptBuilder.buildStepPrompt(...);
SkillPromptComposition composition = promptComposer.composeStepPrompt(skillDefinition, stepPromptBody);
String effectivePrompt = appendInvalidActionFeedback(composition.systemPrompt(), invalidActionFeedback);
```

#### 2. Add prompt metadata to step-loop model traces

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java`

**Changes**:
- Pass `SkillPromptComposition` into `callModelForStep(...)` or pass a trace metadata map alongside `stepPrompt`.
- Add prompt metadata to prepared and sent payloads for each step model call.
- Keep attachment descriptors descriptor-only.
- Continue usage extraction with the effective composed prompt.

#### 3. Compose skill prompt into planning calls

**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`

**Changes**:
- Pass the current `YamlSkillDefinition` prompt into planning prompt composition.
- Compose skill prompt before the existing Bifrost planning prompt body.
- Keep Bifrost's planning contract, retry feedback, and JSON output requirements after the skill prompt.
- Add prompt metadata to planning `MODEL_REQUEST_PREPARED` and `MODEL_REQUEST_SENT` payloads.
- Record planning usage with the composed planning system prompt.
- Add tests proving visible tool descriptions still use `description` and do not include `prompt`.

```text
<skill prompt>

You are planning execution for this YAML skill.
Use the skill instructions above to decide the right plan.
<existing Bifrost planning contract and JSON output requirements>
```

#### 4. Make step-loop nullable output examples explicit

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java`

**Changes**:
- Update final-response schema example rendering so nullable fields are visibly nullable.
- For scalar nullable fields, prefer an example such as `null /* or "<string>" */` only if comments are acceptable in prompt examples; otherwise use `null` plus a short adjacent guidance line.
- Keep the final response itself raw JSON-only; avoid putting comment syntax in the required output object if it could confuse the model.
- Add a concise line after the example for nullable paths, for example `Nullable fields may be JSON null: scale_ticket_id, datetime_in`.

### Success Criteria

#### Automated Verification

- [x] Step-loop execution includes skill prompt in normal step model calls.
- [x] Step-loop execution includes skill prompt in final-response-only model calls.
- [x] Skill prompt is not duplicated across invalid-action retries.
- [x] Step-loop prepared and sent trace payloads include prompt metadata.
- [x] Step-loop usage accounting includes skill prompt for execution calls.
- [x] Planning model calls include skill prompt before Bifrost's planning contract.
- [x] Planning prepared and sent trace payloads include prompt metadata.
- [x] Planning usage accounting includes skill prompt when prompt is present.
- [x] Planner visible tools and tool descriptions still use `description` and do not include skill prompt.
- [x] Step-loop final-response schema guidance describes nullable fields clearly.
- [x] Targeted tests pass: `mvn -pl bifrost-spring-boot-starter -Dtest=StepLoopMissionExecutionEngineTest,StepPromptBuilderTest,PlanningServiceTest test`

#### Manual Verification

- [x] Review a planning trace with a prompt-bearing skill and confirm the skill prompt precedes Bifrost's planning contract through trace assertions.
- [x] Review a step-loop trace with a prompt-bearing skill and confirm step execution and final response are prompt-aware through trace assertions.

**Implementation Note**: After this phase and automated verification pass, pause for manual confirmation of planning composition and nullable final-response guidance.

---

## Phase 4: Feedstock Sample and Full Regression Coverage

### Overview

Finalize the feedstock pure YAML skill as the motivating example and cover the full behavior through sample registration and regression tests.

### Changes Required

#### 1. Harden feedstock pure YAML skill

**File**: `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml`

**Changes**:
- Keep `description` concise and planner/tool-facing.
- Keep `prompt` long-form and execution-facing, based on `FeedstockFormExtractionService`.
- Ensure prompt includes:
  - high-precision weighmaster certificate parser role
  - handwriting vs print guidance
  - tag, red ticket ID, scale stamp, and handwritten data extraction goals
  - Gross - Tare = Net validation
  - return `null` instead of guessing
  - ISO-8601 local datetime guidance
- Keep image attachment input and `model: openai-vision`.
- Keep field-level output schema descriptions.
- Keep `nullable: true` on uncertain/missing feedstock fields.
- Keep JSON-only linter.

#### 2. Verify sample model configuration

**File**: `bifrost-sample/src/main/resources/application.yml`

**Changes**:
- Keep `openai-vision` mapped to provider `openai` and provider model `gpt-5-mini`.
- Add or update sample tests asserting this mapping if the sample test infrastructure exposes configuration.

#### 3. Add sample/catalog tests

**Files**:
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/...`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`

**Changes**:
- Verify `feedstockTicketParserBySkill` registers.
- Verify it has a nonblank prompt.
- Verify it has an image attachment input.
- Verify nullable output fields load as nullable.
- Verify existing Java-backed `feedstockTicketParser` does not need or declare a YAML `prompt`.

#### 4. Logging and trace regression checks

**Files**:
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceContractTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/usage/ModelUsageExtractorTest.java` if usage helper coverage is needed
- Existing execution engine tests

**Changes**:
- Assert trace prompt metadata is present where expected.
- Assert trace still avoids raw attachment bytes/base64 in prompt-bearing attachment runs.
- Add logging coverage only if existing test infrastructure can capture normal logs without brittle global configuration; otherwise document the policy and rely on code review that no normal log emits prompt text.

### Success Criteria

#### Automated Verification

- [x] Feedstock pure YAML skill registers with prompt, image attachment input, nullable fields, and JSON linter.
- [x] `openai-vision` sample model resolves to `gpt-5-mini`.
- [x] Existing Java-backed feedstock skill still registers and runs through its existing tests.
- [x] Prompt trace metadata is covered in single-shot and step-loop tests.
- [x] No trace payload for attachment runs contains raw bytes or base64.
- [x] Starter targeted suite passes: `mvn -pl bifrost-spring-boot-starter "-Dtest=MissionExecutionEngineTest,StepLoopMissionExecutionEngineTest,PlanningServiceTest,StepPromptBuilderTest,OutputSchemaCallAdvisorTest,YamlSkillCatalogTests,LinterCallAdvisorTest" test`
- [x] Sample module passes with dependencies: `mvn -pl bifrost-sample -am test`
- [x] Full affected-module reactor tests pass before merge: `mvn -pl bifrost-sample -am test`

#### Manual Verification

- [x] Provider-backed pure YAML feedstock invocation deferred until credentials are configured; sample registration and schema contract are covered by tests.
- [x] Provider-backed null behavior verification deferred until credentials are configured; nullable schema and prompt contract are covered by tests.
- [x] Normal log inspection documented as manual/code-review policy; no normal-level log statement emits full prompt text.

**Implementation Note**: After this phase and automated verification pass, run provider-backed manual verification only when credentials are available.

---

## Testing Strategy

### Unit Tests

- Manifest parsing accepts optional `prompt` and normalizes blank prompt to `null`.
- Catalog validation rejects `prompt` with `mapping.target_id`.
- Capability registration keeps `description` on metadata/tool surfaces.
- Prompt composer covers no-prompt, default execution, planned execution, planning, and step execution compositions.
- Step prompt builder renders nullable final-response guidance.
- Output schema validator accepts `null` only when `nullable: true`.
- Output schema prompt augmentor describes nullable fields as nullable.

### Integration Tests

- Single-shot mission execution sends composed prompt, records prompt metadata, and includes prompt text in usage accounting.
- Step-loop execution sends composed prompt for each step and final response, records prompt metadata, and avoids duplicate prompt text on retries.
- Planning model calls include skill prompt, while planner visible tools continue to use `description`.
- Advisors append schema/linter/evidence guidance after prompt-composed system text.
- Feedstock sample skill registers with the intended prompt, attachment input, nullable output schema, linter, and vision model.

### Manual Testing Steps

1. Run the targeted starter tests after each phase.
2. Run `mvn -pl bifrost-sample -am test` after sample changes.
3. Inspect one single-shot trace payload for prompt metadata.
4. Inspect one planning trace payload and one step-loop trace payload for prompt metadata and ordering.
5. With provider credentials, invoke `feedstockTicketParserBySkill` against `feedstock-p1.jpg` and verify schema-shaped JSON with `null` for uncertain fields.

Use `ai/commands/3_testing_plan.md` before implementation for the dedicated testing plan artifact.

## Performance Considerations

- Prompt text increases prompt tokens for model calls that receive it; usage accounting should reflect this naturally because the composed system prompt is already passed to the usage extractor.
- Do not duplicate prompt text across retry loops, step-loop prompt builders, or trace metadata beyond the explicit fields required.
- Avoid normal log emission of full prompt text to prevent noisy logs and accidental sensitive instruction exposure.
- Trace payload size will increase when `skillPrompt` is present; this is intentional for debuggability, but only model request payloads for calls that receive the prompt should include it.

## Migration Notes

- Existing YAML skills without `prompt` continue to load and execute with prior prompt behavior.
- Existing mapped YAML skills without `prompt` continue to work.
- Any mapped YAML skill that has adopted `prompt` during the spike must remove it or move the behavior into its Java target.
- Existing capability metadata, tool descriptions, planning visible tool lists, and human-readable catalogs remain `description`-driven.
- Nullable output schema support uses `nullable: true`; JSON Schema union types remain unsupported.
- Existing trace consumers should tolerate the added prompt metadata fields as additive payload keys.

## References

- Original ticket/requirements: `ai/thoughts/tickets/eng-first-class-yaml-skill-prompts.md`
- Related research: `ai/thoughts/research/2026-05-18-first-class-yaml-skill-prompts.md`
- Planning command: `ai/commands/2_create_plan.md`
- Testing plan command: `ai/commands/3_testing_plan.md`
- YAML manifest prompt field: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:19`
- YAML definition prompt accessor: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java:49`
- Catalog load/validation path: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:139`
- Capability metadata uses description: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:47`
- Single-shot prompt composition spike: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:414`
- Step-loop prompt builder call: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:430`
- Step-loop final-response schema examples: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:279`
- Planning prompt builder: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:512`
- Output schema prompt augmentor: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaPromptAugmentor.java:14`
- Output schema nullable validation: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaValidator.java:59`
- Feedstock pure YAML skill: `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml:7`
- Vision sample model mapping: `bifrost-sample/src/main/resources/application.yml:51`
