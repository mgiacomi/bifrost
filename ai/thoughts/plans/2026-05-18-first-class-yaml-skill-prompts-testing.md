# First-Class Prompt Instructions for YAML Skills Testing Plan

## Change Summary

- YAML skills can declare optional top-level `prompt` text as model-facing skill instructions.
- `prompt` is scoped to the skill currently making a model call.
- `prompt` composes into single-shot execution, planning, step execution, and step-loop final response model calls.
- Bifrost appends call-specific contracts after the skill prompt so planning JSON, execution, output schema, linter, and evidence requirements remain authoritative.
- `description` remains the public capability/tool/planner-selection summary and is not replaced by `prompt`.
- YAML skills with `mapping.target_id` must fail catalog validation if they also declare `prompt`.
- Trace request payloads gain prompt metadata: `skillPromptPresent`, exact `skillPrompt` when present, and `promptComposition`.
- Usage accounting includes prompt text for model calls that receive it.
- Nullable output schema support remains in scope as an output-schema-only companion feature.
- The feedstock pure YAML sample keeps concise `description`, long-form `prompt`, image attachment input, nullable output fields, JSON linter, and `openai-vision` model configuration.

## Impacted Areas

- YAML manifest and catalog:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
- Capability and tool metadata:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/PlanQualityValidator.java`
- Prompt composition and model-call runtime:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java`
  - New prompt helper package proposed by the implementation plan: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/prompt/*`
- Advisors and validation:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaPromptAugmentor.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaValidator.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/evidence/EvidenceContractCallAdvisor.java`
- Trace and usage:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/usage/ModelUsageExtractor.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/usage/DefaultSessionUsageService.java`
- Nested skill invocation:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java`
- Sample app:
  - `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml`
  - `bifrost-sample/src/main/resources/application.yml`
  - `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/*`

## Risk Assessment

- Planning omission: planner calls could continue to ignore `prompt`, producing plans that do not honor skill-level behavior.
- Contract ordering regression: planner output instructions could appear before the skill prompt or be weakened by the skill prompt, causing invalid planning responses.
- Prompt leakage across skill boundaries: a parent skill prompt could accidentally be inherited by a child skill call, or a child prompt could leak back into the parent final response call.
- Tool metadata pollution: long `prompt` text could accidentally enter capability metadata, tool descriptions, visible tool lists, or plan quality validation summaries.
- Mapped-skill ambiguity: mapped YAML skills could silently accept `prompt` even though Java target execution owns behavior.
- Trace inconsistency: single-shot, planning, and step-loop traces could expose different prompt metadata fields or omit exact `skillPrompt`.
- Usage undercounting: usage estimation could record the old base prompt for planning or step-loop calls instead of the composed prompt.
- Retry duplication: invalid-action retry feedback or advisor retries could duplicate skill prompt text.
- Advisor ordering: output schema, linter, or evidence hints could replace rather than append to skill prompt text.
- Nullable drift: nullable output support could work in single-shot advisor guidance but remain unclear in step-loop final-response examples.
- Logging leakage: normal logs could accidentally include long or sensitive prompt text.
- Sample drift: feedstock YAML could lose concise `description`, prompt guidance, nullable semantics, or vision model mapping.

## Existing Test Coverage

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java` already has `prependsSkillPromptToSingleShotExecutionPrompt(...)` and mission trace coverage, but it does not yet assert prompt trace metadata.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java` covers planning request payloads, traces, usage, and attachment descriptors, but planning currently does not receive `YamlSkillDefinition.prompt()`.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngineTest.java` covers step-loop calls, retries, final responses, trace events, and attachments, but not skill prompts in step/final-response calls.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilderTest.java` covers step prompt shape and final-response schema guidance, but not nullable examples.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java` covers strict YAML loading, schema loading, attachment schema validation, and catalog failures, but not prompt-specific catalog semantics.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java` covers YAML capability registration and tool schemas, but not prompt exclusion from metadata/tool descriptions.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java` covers nullable field validation and output schema prompt guidance.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/linter/LinterCallAdvisorTest.java` covers linter retry prompt mutation.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java` already has nested YAML skill execution coverage, including parent plan restoration and separate parent/child chat clients.
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java` and `SampleControllerTest.java` cover sample startup and controller behavior.

## Gaps

- No test proves planning receives `prompt`.
- No test proves planner JSON contract instructions appear after the skill prompt.
- No test proves planning trace payloads include prompt metadata.
- No test proves planning usage includes the composed prompt.
- No test proves step-loop model calls receive `prompt`.
- No test proves step-loop final-response-only calls receive `prompt`.
- No test proves prompt text is not duplicated across invalid-action retries.
- No test proves prompt metadata is consistent across single-shot, planning, and step-loop calls.
- No test proves parent and child YAML skill prompts stay scoped to their own model calls.
- No test proves `prompt` is rejected on mapped YAML skills.
- No test proves capability metadata, tool descriptions, visible tool lists, and plan quality summaries exclude `prompt`.
- No test proves normal logs avoid full prompt text.
- No test proves feedstock pure YAML sample has all prompt-related contract details.

## Bug Reproduction / Failing Test First

### Primary Failing Test

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`
- Name: `planningPromptIncludesSkillPromptBeforePlanningContract`
- Arrange:
  - Create a `YamlSkillDefinition` whose manifest has `planning_mode: true`, `description: "Short planner-facing summary"`, and `prompt: "PARENT_PROMPT_SENTINEL\nAlways verify totals before final response."`.
  - Use `DefaultPlanningService` with `DefaultExecutionStateService` and a capture-capable `SequencePlanningChatClient`.
  - Provide one visible tool with description `"Short child tool description"`.
- Act:
  - Call `initializePlan(...)`.
- Assert:
  - Captured planning system prompt starts with or contains the skill prompt before `"Create an ordered flight plan"`.
  - Captured planning system prompt contains Bifrost planning JSON/output instructions after the skill prompt.
  - Captured planning system prompt contains `"Short child tool description"` but does not include any prompt text as a tool description.
  - `MODEL_REQUEST_PREPARED` and `MODEL_REQUEST_SENT` contain `skillPromptPresent=true`, `skillPrompt="PARENT_PROMPT_SENTINEL..."`, and `promptComposition=skill_prompt_plus_planning_prompt`.
  - Usage service records a prompt unit count greater than the same call without a prompt, or a test extractor spy receives the composed planning prompt.
- Expected failure pre-fix:
  - Planning system prompt does not contain `PARENT_PROMPT_SENTINEL`, trace metadata is absent, and usage is recorded against the old planning prompt.

### Secondary Failing Test

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- Name: `nestedYamlSkillPromptsStayScopedToTheirOwnModelCalls`
- Arrange:
  - Use the existing nested YAML skill setup with `root.visible.skill` calling `child.llm.skill`.
  - Set root manifest prompt to `PARENT_PROMPT_SENTINEL`.
  - Set child manifest prompt to `CHILD_PROMPT_SENTINEL`.
  - Use separate `FakeCoordinatorChatClient` instances for root and child.
- Act:
  - Execute `root.visible.skill`.
- Assert:
  - Root planning/execution/final-response system messages contain `PARENT_PROMPT_SENTINEL` and do not contain `CHILD_PROMPT_SENTINEL`.
  - Child planning/execution/final-response system messages contain `CHILD_PROMPT_SENTINEL` and do not contain `PARENT_PROMPT_SENTINEL`.
  - Root tool result may contain child returned data, but not child prompt text.
  - Trace records under root and child frames carry the correct prompt metadata for their own skill only.
- Expected failure pre-fix:
  - Current code does not compose prompts into planning/step-loop at all; after partial implementation this test guards against over-broad prompt propagation.

### Catalog Failing Test

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- Name: `rejectsPromptOnMappedYamlSkill`
- Arrange:
  - Add invalid YAML fixture `skills/invalid/mapped-skill-with-prompt.yaml` with `mapping.target_id` and top-level `prompt`.
- Act:
  - Start catalog with that fixture.
- Assert:
  - Startup fails with a message naming `mapped-skill-with-prompt.yaml`, field `prompt`, and `mapping.target_id`.
- Expected failure pre-fix:
  - Catalog loads the skill because no mapped-skill prompt validation exists.

## Tests to Add/Update

### 1) `loadsYamlSkillPromptAndNormalizesBlankPrompt`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - Nonblank top-level `prompt` loads into `YamlSkillDefinition.prompt()`.
  - Blank or whitespace-only `prompt` normalizes to `null`.
  - Strict parsing still rejects unknown prompt-like fields such as `system_prompt`.
- Fixtures/data:
  - Valid YAML fixture with prompt.
  - Valid YAML fixture with blank prompt.
  - Invalid YAML fixture with `system_prompt`.
- Mocks:
  - Existing `ApplicationContextRunner` pattern.

### 2) `rejectsPromptOnMappedYamlSkill`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - `prompt` is a configuration error when `mapping.target_id` is present.
- Fixtures/data:
  - `skills/invalid/mapped-skill-with-prompt.yaml`.
- Mocks:
  - Existing catalog test pattern.

### 3) `registersYamlCapabilityUsingDescriptionNotPrompt`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
- What it proves:
  - `CapabilityMetadata.description()`, `CapabilityToolDescriptor.description()`, and generated tool callback description use `description`.
  - Long `prompt` text is absent from tool descriptions and metadata.
- Fixtures/data:
  - YAML skill with `description: "Short public summary"` and `prompt: "LONG_PROMPT_SENTINEL"`.
- Mocks:
  - Existing context runner.

### 4) `planningPromptIncludesSkillPromptBeforePlanningContract`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`
- What it proves:
  - Planner system prompt includes skill prompt.
  - Planner-specific JSON/output contract appears after the skill prompt.
  - Tool descriptions still come from `description`.
- Fixtures/data:
  - `YamlSkillDefinition` with `prompt`.
  - Capture-capable `SequencePlanningChatClient`.
- Mocks:
  - Existing planning fake chat client.

### 5) `planningTraceIncludesSkillPromptMetadata`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`
- What it proves:
  - Planning `MODEL_REQUEST_PREPARED` and `MODEL_REQUEST_SENT` payloads include `skillPromptPresent`, exact `skillPrompt`, and `promptComposition`.
  - No-prompt planning calls include `skillPromptPresent=false` and an appropriate no-skill-prompt composition descriptor.
- Fixtures/data:
  - One prompt-bearing definition and one no-prompt definition.
- Mocks:
  - Existing `SimpleChatClient` or `SequencePlanningChatClient`.

### 6) `planningUsageUsesComposedPlanningPrompt` (deferred explicit spy coverage)

- Type: unit/integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`
- What it proves:
  - Planning usage accounting receives the composed planning prompt, including `skillPrompt`.
- Status:
  - Deferred. Runtime usage receives the same composed system prompt used for planning calls; this was verified through execution-path coverage rather than a dedicated `ModelUsageExtractor` spy.
- Fixtures/data:
  - `RecordingSessionUsageService` already used in `PlanningServiceTest`.
  - Either compare prompt units with and without prompt, or inject a spy/stub `ModelUsageExtractor` that records the system prompt argument.
- Mocks:
  - Prefer a spy/stub extractor if constructor access makes that cleaner than token-count comparisons.

### 7) `prependsSkillPromptToSingleShotTraceMetadata`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
- What it proves:
  - Existing single-shot prompt behavior remains.
  - Prepared and sent trace payloads include prompt metadata.
  - No-prompt single-shot calls retain semantically unchanged default prompt and `skillPromptPresent=false`.
- Fixtures/data:
  - Existing `definitionWithPrompt()`.
  - Existing `MissionChatClient`.
- Mocks:
  - Existing mocked `PlanningService`.

### 8) `stepLoopIncludesSkillPromptInStepAndFinalResponsePrompts`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngineTest.java`
- What it proves:
  - Normal step calls contain skill prompt.
  - Final-response-only calls contain skill prompt.
  - Existing step contract text remains after the skill prompt.
- Fixtures/data:
  - `YamlSkillDefinition` with `prompt`.
  - `SequenceChatClient` with a CALL_TOOL response followed by FINAL_RESPONSE.
- Mocks:
  - Existing `InitializingPlanningService`.

### 9) `stepLoopDoesNotDuplicateSkillPromptAcrossInvalidActionRetries`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngineTest.java`
- What it proves:
  - Invalid-action retry feedback appends after the composed system prompt.
  - Each retry system prompt contains exactly one occurrence of the skill prompt sentinel.
- Fixtures/data:
  - Existing invalid-action retry flow.
  - Prompt sentinel such as `STEP_PROMPT_SENTINEL`.
- Mocks:
  - Existing sequence fake chat client.

### 10) `stepLoopTraceIncludesSkillPromptMetadata`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngineTest.java`
- What it proves:
  - Step model `MODEL_REQUEST_PREPARED` and `MODEL_REQUEST_SENT` payloads include prompt metadata.
  - Metadata is present for final-response-only calls as well.
- Fixtures/data:
  - Prompt-bearing step-loop definition.
- Mocks:
  - Existing sequence fake chat client.

### 11) `nestedYamlSkillPromptsStayScopedToTheirOwnModelCalls`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorTest.java`
- What it proves:
  - Parent prompt applies only to parent skill model calls.
  - Child prompt applies only to child skill model calls.
  - Parent final response does not inherit child prompt.
  - Child call does not inherit parent prompt.
- Fixtures/data:
  - Existing nested `root.visible.skill` and `child.llm.skill` setup.
  - Root sentinel `PARENT_PROMPT_SENTINEL`.
  - Child sentinel `CHILD_PROMPT_SENTINEL`.
- Mocks:
  - Existing `MultiClientSkillChatClientFactory` and `FakeCoordinatorChatClient`; extend fake if needed to expose system messages by call segment.

### 12) `outputSchemaAdvisorPreservesSkillPrompt`

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
- What it proves:
  - Output schema augmentation appends guidance after a system prompt that already contains skill prompt text.
  - Retry hints preserve the original skill prompt.
- Fixtures/data:
  - Prompt with sentinel.
  - Existing schema fixtures.
- Mocks:
  - Existing fake advisor chain.

### 13) `linterAdvisorPreservesSkillPrompt`

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/linter/LinterCallAdvisorTest.java`
- What it proves:
  - Linter retry system prompt keeps skill prompt text.
  - Linter hints append after existing prompt.
- Fixtures/data:
  - Prompt with sentinel.
  - Existing regex linter fixture.
- Mocks:
  - Existing fake advisor chain.

### 14) `evidenceAdvisorPreservesSkillPrompt`

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/evidence/EvidenceContractCallAdvisorTest.java` if present; otherwise create it near existing evidence tests
- What it proves:
  - Evidence retry hints preserve and append after skill prompt text.
- Fixtures/data:
  - Evidence contract that forces a retry.
  - Prompt with sentinel.
- Mocks:
  - Minimal advisor chain fake modeled after output schema/linter tests.

### 15) `stepPromptBuilderListsNullableOutputFields`

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilderTest.java`
- What it proves:
  - Final-response schema guidance clearly permits JSON `null` for nullable fields.
  - Non-nullable fields are not listed as nullable.
  - The JSON-only final response instruction remains intact.
- Fixtures/data:
  - Output schema with nullable string/date fields and non-nullable boolean/number fields.
- Mocks:
  - None.

### 16) `outputSchemaNullableValidationRemainsOutputOnly`

- Type: unit/integration
- Location:
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - JSON `null` passes for nullable output fields and fails for non-nullable fields.
  - `nullable` in input schemas remains rejected unless separately designed.
- Fixtures/data:
  - Existing nullable schema fixture.
  - Invalid YAML input schema fixture with `nullable: true`.
- Mocks:
  - Existing patterns.

### 17) `feedstockPureYamlSkillHasPromptAttachmentAndNullableSchema`

- Type: integration
- Location: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java` or a new `SampleSkillCatalogTest`
- What it proves:
  - Sample app starts with `feedstockTicketParserBySkill`.
  - The pure YAML skill has nonblank prompt text containing the key extraction behaviors.
  - It has image attachment input.
  - Uncertain output fields are nullable.
  - `openai-vision` resolves to `gpt-5-mini`.
- Fixtures/data:
  - Real sample YAML and `application.yml`.
- Mocks:
  - Existing sample app test wiring.

### 18) `normalLogsDoNotContainFullSkillPrompt` (manual/code-review policy)

- Type: integration, only if log capture is stable
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java` or a focused logging test
- What it proves:
  - Normal log output for a prompt-bearing call does not contain the full prompt sentinel.
  - Debug-only logging, if added, records presence/length rather than content.
- Status:
  - Manual/code-review policy for this ticket. No production normal-level log statement emits the full skill prompt; a log-capture test was not added because the current prompt work is trace-focused and log capture would be brittle.
- Fixtures/data:
  - Prompt sentinel unlikely to appear elsewhere.
- Mocks:
  - Spring Boot `OutputCaptureExtension` if it is not brittle for this runtime path.

## How to Run

- Catalog and registrar tests:
  - `mvn -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests,YamlSkillCapabilityRegistrarTests test`
- Single-shot prompt and trace tests:
  - `mvn -pl bifrost-spring-boot-starter -Dtest=MissionExecutionEngineTest test`
- Planning prompt tests:
  - `mvn -pl bifrost-spring-boot-starter -Dtest=PlanningServiceTest test`
- Step-loop prompt tests:
  - `mvn -pl bifrost-spring-boot-starter -Dtest=StepLoopMissionExecutionEngineTest,StepPromptBuilderTest test`
- Advisor and nullable tests:
  - `mvn -pl bifrost-spring-boot-starter -Dtest=OutputSchemaCallAdvisorTest,LinterCallAdvisorTest,EvidenceContractCallAdvisorTest test`
- Nested skill scope test:
  - `mvn -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest test`
- Focused starter suite:
  - `mvn -pl bifrost-spring-boot-starter clean "-Dtest=YamlSkillCatalogTests,YamlSkillCapabilityRegistrarTests,MissionExecutionEngineTest,PlanningServiceTest,StepLoopMissionExecutionEngineTest,StepPromptBuilderTest,OutputSchemaCallAdvisorTest,LinterCallAdvisorTest,ExecutionCoordinatorTest" test`
- Sample tests:
  - `mvn -pl bifrost-sample -am test`
- Full verification:
  - `mvn test`

## Exit Criteria

- [x] Primary failing test exists and fails pre-fix because planning does not receive skill prompt.
- [x] Catalog failing test exists and fails pre-fix because mapped YAML skills with `prompt` are accepted.
- [x] Nested prompt-scope test exists and protects parent/child prompt boundaries.
- [x] Catalog tests prove prompt loading, blank normalization, strict unknown-field handling, and mapped-skill rejection.
- [x] Capability/tool tests prove `description` remains the only public/tool/planner-selection summary.
- [x] Single-shot tests prove prompt ordering, trace metadata, and usage accounting through the composed system prompt path.
- [x] Planning tests prove prompt ordering before Bifrost planning contract, trace metadata, and description-only tool surfaces.
- [x] Step-loop tests prove prompt inclusion in step and final-response calls, no retry duplication, and trace metadata.
- [x] Advisor tests prove output schema, linter, and evidence hints preserve and append after skill prompt text.
- [x] Nullable tests prove output-schema `nullable: true` validation and final-response guidance, with input-schema nullable still out of scope.
- [x] Trace tests prove exact prompt metadata is recorded where intended.
- [x] Normal log full-prompt exclusion is documented as a manual/code-review policy; no normal-level log statement emits full prompt text.
- [x] Feedstock sample tests prove prompt, attachment input, nullable schema, JSON linter, and `gpt-5-mini` vision mapping.
- [x] `mvn -pl bifrost-sample -am test` passes.
- [x] Full reactor-equivalent verification for affected modules passes with `mvn -pl bifrost-sample -am test`; standalone `mvn test` is not required because the reactor currently includes the same affected modules.

## Manual Verification

- Inspect a single-shot trace for a prompt-bearing skill and confirm `system`, `skillPromptPresent`, `skillPrompt`, and `promptComposition` are present and coherent.
- Inspect a planning trace for a prompt-bearing `planning_mode: true` skill and confirm the skill prompt precedes Bifrost's planning contract.
- Inspect a step-loop trace and confirm step execution and final response calls use the skill prompt once.
- Inspect a nested parent/child skill run and confirm parent and child prompt sentinels stay scoped to their own model-call frames.
- With provider credentials configured, invoke the pure YAML feedstock sample and confirm schema-shaped JSON output with JSON `null` for uncertain fields.
- Inspect normal application logs from a prompt-bearing run and confirm full prompt text is absent.
