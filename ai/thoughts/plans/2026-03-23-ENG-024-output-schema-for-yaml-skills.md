# ENG-024 Output Schema Support For YAML Skills Implementation Plan

## Overview

Add first-class `output_schema` support to YAML-defined skills so Bifrost can instruct LLM-backed YAML skills to return JSON only, validate the response against a supported schema subset, retry bounded times on parse/schema failures, and throw a dedicated terminal exception that preserves the raw model output and validation issues.

The implementation should fit the current YAML-skill architecture instead of overloading the existing regex linter path. Successful executions must continue returning the original model text as a `String`, while `output_schema` composes with regex linting when both are configured.

## Current State Analysis

`YamlSkillManifest` currently models `name`, `description`, `model`, `thinking_level`, `allowed_skills`, `rbac_roles`, `planning_mode`, `linter`, and `mapping`, but not any structured-output contract fields (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:11`). Because the manifest and nested manifest types use `@JsonIgnoreProperties(ignoreUnknown = false)`, any new YAML keys must be explicitly modeled or they will fail startup during deserialization (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:10`).

`YamlSkillCatalog` is the startup boundary for YAML skill discovery, manifest deserialization, required-field validation, model resolution, and linter validation (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:25`). Its current validation flow already enforces bounded retry counts and reports field-specific startup failures, which makes it the correct place to validate the supported `output_schema` subset and to apply defaults such as `output_schema_max_retries = 2` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:97`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:153`).

At runtime, output validation is currently limited to the regex linter advisor path. `DefaultSkillAdvisorResolver` only creates `LinterCallAdvisor` instances from the YAML `linter` block (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:22`). `LinterCallAdvisor` already implements the retry loop pattern we want to mirror or generalize: call model, inspect raw assistant text, record retry/exhausted state, append a system hint, and retry until success or exhaustion (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java:45`).

`SpringAiSkillChatClientFactory` attaches manifest-derived advisors to per-skill `ChatClient` instances, while `DefaultMissionExecutionEngine` builds the system prompt, invokes the `ChatClient`, and returns the raw text response without any post-processing (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:43`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:77`). That split means output-schema enforcement is best introduced as a new advisor and a small prompt augmentation step, rather than by changing the mission engine to parse/transform responses directly.

Existing coverage is concentrated in `YamlSkillCatalogTests`, which already verifies manifest parsing, unknown-field failures, retry bounds, and regex linter validation (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:31`). ENG-024 will need both catalog-level tests and new runtime advisor tests.

## Desired End State

YAML skills may optionally declare:

```yaml
output_schema:
  type: object
  properties:
    vendorName:
      type: string
  required:
    - vendorName
  additionalProperties: false

output_schema_max_retries: 2
```

When present, Bifrost should:

1. Load and validate the schema subset at startup with clear field-path errors.
2. Default `output_schema_max_retries` to `2`, reject it when `output_schema` is absent, and reject unsupported schema keywords.
3. Augment the model instructions so the skill returns JSON only and follows the declared field names and types.
4. Validate each model response in this order:
   1. JSON parse
   2. schema validation
   3. regex linting, when configured
5. Retry parse/schema failures up to the configured bound using concise issue-based retry hints.
6. Throw `BifrostOutputSchemaValidationException` after terminal schema failure, preserving the raw model output and structured validation issues.
7. Return the original raw JSON string unchanged when validation succeeds.

### Key Discoveries

- Manifest unknown-field rejection is already strict, so `output_schema` must be modeled as typed nested manifest classes instead of a loose map (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:10`).
- The catalog already owns startup validation and field-specific error shaping, so output-schema subset enforcement belongs there beside linter validation (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:97`).
- The current retry pattern is advisor-based, not mission-engine-based, which favors implementing schema retries in a new advisor that composes ahead of the regex linter (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:22`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java:49`).
- `DefaultMissionExecutionEngine` already returns model text as-is, so preserving the successful return type as `String` is naturally aligned with the current runtime contract (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:90`).

## What We're NOT Doing

- Adding `output_schema` support to `@SkillMethod` Java skills.
- Supporting full JSON Schema dialect features such as `$ref`, `oneOf`, `anyOf`, `allOf`, `not`, conditional subschemas, or `patternProperties`.
- Supporting root-array schemas.
- Repairing, coercing, or normalizing invalid JSON output.
- Reformatting successful JSON before returning it.
- Depending on provider-native structured output or response-format APIs for the MVP.
- Replacing or refactoring the existing regex linter into the schema feature; they remain separate features that compose in sequence.

## Implementation Approach

Introduce `output_schema` as a typed YAML-manifest feature with a validated JSON-Schema-inspired subset. Keep startup validation in `YamlSkillCatalog`, runtime retries in a dedicated advisor, and successful response semantics unchanged.

The cleanest implementation path is:

1. Extend the manifest model with a typed schema tree and retry field.
2. Add catalog validation that enforces supported keywords, case-insensitive property-name uniqueness, supported nesting rules, manifest-time defaults such as `additionalProperties = false`, and complexity warnings.
3. Add a dedicated output-schema runtime package that:
   - parses JSON,
   - validates the parsed tree against the supported subset,
   - produces structured issues and failure modes,
   - throws `BifrostOutputSchemaValidationException` on exhaustion.
4. Resolve a new advisor before the regex linter so schema validation happens first and the same advisor can also append the initial structured-output prompt guidance before the first model call.
5. Record useful-but-bounded schema-validation metadata in session/journal state so operators can reconstruct what failed without introducing a heavy diagnostics API.

This keeps each concern in its current architectural layer and avoids coupling mission execution to schema semantics.

## Phase 1: Manifest Model And Startup Validation

### Overview

Teach the YAML manifest system to model `output_schema` and `output_schema_max_retries`, then validate the supported subset at startup with the same field-specific error quality as the current linter path.

### Changes Required:

#### 1. Manifest model extensions
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
**Changes**: Add typed manifest fields for `output_schema` and `output_schema_max_retries`, plus nested schema-node classes for the supported subset.

```java
@JsonProperty("output_schema")
private OutputSchemaManifest outputSchema;

@JsonProperty("output_schema_max_retries")
private Integer outputSchemaMaxRetries;

@JsonIgnoreProperties(ignoreUnknown = false)
public static final class OutputSchemaManifest {
    private String type;
    private Map<String, OutputSchemaManifest> properties = Map.of();
    private List<String> required = List.of();
    private Boolean additionalProperties;
    private OutputSchemaManifest items;
    private List<String> enumValues = List.of();
    private String description;
    private String format;
}
```

#### 2. Catalog validation and defaulting
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
**Changes**: Add `validateOutputSchema(...)`, enforce retry defaults/range, normalize `additionalProperties` to `false` during manifest loading/validation when omitted, reject unsupported keywords through typed deserialization, ensure root `type: object`, validate `required` membership, enforce case-insensitive property uniqueness, and log warnings for overly complex but supported schemas using code constants for the MVP.

```java
private static final int DEFAULT_OUTPUT_SCHEMA_RETRIES = 2;
private static final int MAX_OUTPUT_SCHEMA_RETRIES = 3;

private void validateOutputSchema(Resource resource, YamlSkillManifest manifest) {
    if (manifest.getOutputSchema() == null && manifest.getOutputSchemaMaxRetries() != null) {
        throw invalidSkill(resource, "output_schema_max_retries",
                "may only be configured when output_schema is present");
    }
    // validate root object, supported child shapes, required fields, case-insensitive uniqueness,
    // normalize additionalProperties defaults, and apply warning thresholds via static constants.
}
```

#### 3. Catalog test coverage
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
**Changes**: Add startup tests for valid schemas, default retries, unsupported keywords, root-array rejection, missing required properties, duplicate property names by case, and warning-only complex schemas.

```java
@Test
void defaultsOutputSchemaMaxRetriesToTwoWhenSchemaIsPresent() { ... }

@Test
void failsStartupWhenOutputSchemaContainsCaseInsensitiveDuplicateProperties() { ... }
```

### Success Criteria:

#### Automated Verification:
- [x] Catalog tests pass for the new manifest contract: `mvn -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] Starter test suite still passes after manifest validation changes: `mvn -pl bifrost-spring-boot-starter test`
- [x] Project compiles with the new manifest types: `mvn test -DskipTests`

#### Manual Verification:
- [ ] A sample YAML skill with a supported inline `output_schema` loads successfully at startup.
- [ ] A YAML skill using an unsupported keyword fails startup with a precise field path.
- [ ] A schema with high nesting or large property count emits a warning without blocking startup.
- [ ] `output_schema_max_retries` defaults to `2` when omitted and is rejected when no schema exists.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Runtime Validation, Retries, And Exception Handling

### Overview

Add the structured-output runtime path: JSON parsing, schema validation, bounded retries with concise issue hints, structured observability, and a dedicated terminal exception.

### Changes Required:

#### 1. Output-schema runtime package
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/...`
**Changes**: Add a small runtime package for schema validation concerns, including failure modes, issue records, validator, retry-hint builder, and terminal exception.

```java
public enum OutputSchemaFailureMode {
    INVALID_JSON,
    SCHEMA_VALIDATION_FAILED
}

public record OutputSchemaValidationIssue(String path, String message, String canonicalField) { }

public final class BifrostOutputSchemaValidationException extends RuntimeException {
    private final String skillName;
    private final String rawOutput;
    private final List<OutputSchemaValidationIssue> validationIssues;
    private final int attemptCount;
    private final int maxRetries;
    private final OutputSchemaFailureMode failureMode;
}
```

#### 2. Schema advisor
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisor.java`
**Changes**: Implement a call advisor that mirrors the linter retry loop, injects the initial JSON-only/schema-summary system guidance before the first model call, validates parse + schema first, appends concise issue-based retry hints, records outcomes in response context/session state, and throws `BifrostOutputSchemaValidationException` on exhaustion.

```java
while (true) {
    ChatClientResponse response = callAdvisorChain.nextCall(currentRequest);
    String candidate = extractAssistantText(response);
    ValidationResult result = validator.validate(candidate, schema);
    if (result.passed()) {
        return record(response, passedOutcome(...));
    }
    if (attempt > maxRetries) {
        throw new BifrostOutputSchemaValidationException(...);
    }
    currentRequest = currentRequest.mutate()
            .prompt(appendHint(currentRequest.prompt(), retryHintBuilder.build(result)))
            .build();
    attempt++;
}
```

#### 3. Advisor resolution ordering
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java`
**Changes**: Resolve the new output-schema advisor when a manifest has `output_schema`, place it before the regex linter advisor, and leave the regex path unchanged except for composition ordering.

```java
List<Advisor> advisors = new ArrayList<>();
if (definition.outputSchema() != null) {
    advisors.add(outputSchemaAdvisor(...));
}
if (definition.linter() != null) {
    advisors.add(regexLinterAdvisor(...));
}
return List.copyOf(advisors);
```

#### 4. Session/journal observability
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/...`
**Changes**: Add minimal but useful output-schema outcome recording so session/journal state captures the failure mode, attempt count, retry/exhausted status, and a bounded list of canonical validation issues without introducing a full diagnostics surface.

```java
public record OutputSchemaOutcome(
        String skillName,
        OutputSchemaFailureMode failureMode,
        int attempt,
        int maxRetries,
        OutputSchemaOutcomeStatus status,
        List<OutputSchemaValidationIssue> issues) { }
```

This should mirror the linter outcome style closely enough that operators can understand why a run failed while keeping the metadata surface intentionally small.

#### 5. Runtime tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTests.java`
**Changes**: Add focused advisor tests covering invalid JSON retries, schema mismatch retries, ambiguous key casing, exhaustion exception fields, session-state metadata, and linter-after-schema composition.

```java
@Test
void retriesInvalidJsonAndThrowsExceptionWithRawOutputWhenExhausted() { ... }

@Test
void acceptsCaseInsensitiveMatchButRejectsAmbiguousDuplicateKeys() { ... }
```

### Success Criteria:

#### Automated Verification:
- [x] New runtime advisor tests pass: `mvn -pl bifrost-spring-boot-starter -Dtest=OutputSchemaCallAdvisorTests test`
- [x] Catalog and runtime tests pass together: `mvn -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests,OutputSchemaCallAdvisorTests test`
- [x] Starter module test suite passes: `mvn -pl bifrost-spring-boot-starter test`
- [x] Terminal exception exposes raw output and structured issues in tests.
- [x] Session/journal metadata records enough detail to distinguish invalid JSON from schema mismatches in tests.

#### Manual Verification:
- [ ] A YAML skill with `output_schema` retries after prose or malformed JSON output and succeeds when the next response is valid JSON.
- [ ] Missing, unknown, and wrong-type fields produce concise retry hints using canonical schema field names.
- [ ] Ambiguous casing in returned JSON keys fails validation even when either key alone would have matched.
- [ ] Exhausted retries surface a usable exception payload for application debugging.
- [ ] Session/journal records make it clear which attempt failed, why it failed, and whether retries were exhausted.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Prompting, Wiring Polish, Samples, And Regression Coverage

### Overview

Ensure the runtime actually teaches the model how to comply with `output_schema`, wire the feature cleanly into skill execution, add sample manifests, and close the loop with regression coverage and docs.

### Changes Required:

#### 1. Advisor-side prompt augmentation helper
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/OutputSchemaPromptAugmentor.java`
**Changes**: Add an advisor-side helper that injects initial structured-output instructions before the first call so the model is told once to return JSON only, avoid fences/prose, use canonical field names, and omit unknown fields unless allowed.

```java
Prompt promptWithSchemaGuidance = outputSchemaPromptAugmentor.augment(
        originalPrompt,
        definition.outputSchema());
```

This keeps manifest-aware prompting close to the schema-validation advisor and avoids threading `YamlSkillDefinition` deeper into `DefaultMissionExecutionEngine`.

#### 2. Chat-client and auto-configuration wiring
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
**Changes**: Keep skill-specific advisor attachment unchanged except for any new helper dependencies needed by the output-schema advisor path. Update auto-configuration if new validator/prompt-builder beans are introduced.

```java
List<Advisor> advisors = resolvedAdvisors(skillAdvisorResolver.resolve(definition));
builder.defaultOptions(options);
builder.defaultAdvisors(advisors);
```

#### 3. Samples and documentation-facing fixtures
**File**: `bifrost-sample/src/main/resources/skills/...`
**Changes**: Add at least one sample YAML skill manifest showing `output_schema`, `output_schema_max_retries`, and composition with regex linting.

```yaml
output_schema:
  type: object
  properties:
    vendorName:
      type: string
  required: [vendorName]
  additionalProperties: false
output_schema_max_retries: 2
```

#### 4. End-to-end integration coverage
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/...`
**Changes**: Add a higher-level execution test proving prompt augmentation + schema advisor + regex linter sequencing works through the normal YAML skill execution path.

```java
@Test
void returnsOriginalJsonStringAfterSchemaValidationAndRegexLintingPass() { ... }
```

### Success Criteria:

#### Automated Verification:
- [x] End-to-end YAML skill execution tests pass for schema-only and schema-plus-linter paths: `mvn -pl bifrost-spring-boot-starter test`
- [x] Sample manifests load in starter/sample integration scenarios: `mvn test`
- [x] No regressions in existing linter behavior or thinking-level defaults.

#### Manual Verification:
- [ ] A configured skill produces raw JSON without markdown fences in a realistic extraction prompt.
- [ ] The returned payload string is exactly the successful model output, not reformatted by Bifrost.
- [ ] Regex linting still runs after schema validation passes and can reject raw-text issues independently.
- [ ] Sample YAML manifests are understandable enough to serve as developer guidance.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:
- Extend `YamlSkillCatalogTests` to cover the startup contract for `output_schema` and `output_schema_max_retries`.
- Add validator-level tests for supported scalar/object/array combinations, `required` membership, manifest-time `additionalProperties` defaulting, and case-insensitive field matching.
- Add advisor tests for retry counts, retry hints, exhaustion behavior, session/journal outcome recording, and linter ordering.
- Add exception tests to verify `rawOutput`, `validationIssues`, `attemptCount`, `maxRetries`, and `failureMode`.

### Integration Tests:
- Add YAML-skill execution tests that exercise prompt augmentation plus runtime validation through the normal skill execution stack.
- Add composition tests where schema validation succeeds and regex linting fails afterward.
- Add regression tests proving successful output is returned exactly as the model produced it.
- Add observability assertions that distinguish invalid JSON, schema mismatch, retrying, and exhausted outcomes in recorded state.

**Note**: Prefer a dedicated testing plan artifact created via `3_testing_plan.md` for full details, including failing-test-first order, impacted suites, and exit criteria.

### Manual Testing Steps:
1. Create a sample extraction skill with a shallow object schema and confirm valid JSON returns successfully.
2. Trigger a prose or malformed-JSON model response and confirm bounded retries occur with concise issue hints.
3. Trigger valid JSON with wrong field names and confirm canonical-field issue messages are emitted.
4. Enable both `output_schema` and regex linter and confirm regex runs only after schema validation passes.
5. Inspect a terminal failure and confirm the thrown exception preserves the exact raw model output for debugging.

## Performance Considerations

- Parsing and validating JSON on every retry adds CPU cost, but the dominant cost remains LLM latency; MVP complexity warnings should keep schemas small enough that local validation stays cheap.
- Retry hints must cap the number of reported issues to avoid prompt bloat and token growth.
- Prompt augmentation should summarize the schema instead of embedding excessively verbose or repetitive instructions, and it should live in the schema advisor path so the concern stays local.
- Case-insensitive property matching should precompute canonical-name maps once per schema node where possible, rather than scanning properties repeatedly at runtime.

## Migration Notes

- This is an additive manifest feature for YAML skills; existing manifests without `output_schema` should continue loading and executing unchanged.
- Startup behavior becomes stricter only for manifests that opt into `output_schema`.
- The sample project should include a new schema-enabled skill so the feature is discoverable without requiring migration of existing samples.

## References

- Original ticket/requirements: `ai/thoughts/tickets/eng_024_output_schema_for_yaml_skills.md`
- Related research: `ai/thoughts/research/2026-03-23-ENG-024-output-schema-for-yaml-skills.md`
- Current manifest model: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:11`
- Current catalog validation: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:97`
- Current advisor wiring: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java:22`
- Current retry loop pattern: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java:45`
- Current mission execution path: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:77`
- Existing manifest coverage: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:31`
