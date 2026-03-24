# ENG-024 Output Schema Support For YAML Skills Testing Plan

## Change Summary
- Add first-class `output_schema` support for YAML-defined skills.
- Validate the schema subset at startup, including `output_schema_max_retries` and manifest-time defaulting of `additionalProperties` to `false`.
- Add an advisor-driven runtime validation loop for JSON parsing and schema validation before regex linting.
- Add bounded retries with concise retry hints and a terminal `BifrostOutputSchemaValidationException`.
- Record minimal but useful output-schema outcome metadata in session/journal state.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/...`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/outputschema/...`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SkillAdvisorResolverTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/linter/LinterCallAdvisorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorLinterIntegrationTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java`

## Risk Assessment
- Startup validation could become too permissive and accidentally allow unsupported schema keywords or invalid `required` declarations.
- Startup validation could become too strict and reject valid supported schemas, especially nested objects or arrays of simple objects.
- Advisor ordering could regress existing linter behavior if schema validation does not run before regex linting.
- Retry semantics could be off-by-one, causing too many or too few model attempts.
- Prompt augmentation could be missing on the first attempt, reducing model compliance and causing confusing retries.
- Case-insensitive key matching could accidentally accept ambiguous duplicate keys or reject legitimate mixed-case output.
- Session/journal recording could be incomplete, making schema failures harder to debug than current linter failures.
- Successful responses could be reformatted or parsed/re-serialized instead of returning the raw JSON text unchanged.

## Existing Test Coverage
- `YamlSkillCatalogTests` already covers manifest parsing, unknown-field failures, retry range validation, and linter-specific startup validation.
- `LinterCallAdvisorTest` already covers advisor retry sequencing, hint injection, exhaustion behavior, and session-visible outcome recording.
- `SkillAdvisorResolverTests` already verifies resolver behavior for skill-level advisors.
- `ExecutionCoordinatorLinterIntegrationTest` already proves advisor-driven retries work through the normal coordinator/runtime stack and produce journal entries.
- `ExecutionStateServiceTest` already verifies linter outcomes are stored on session state and written to the journal through one boundary.

Current gaps for ENG-024:
- No catalog tests for `output_schema` or `output_schema_max_retries`.
- No runtime validator/advisor tests for invalid JSON, schema mismatches, or structured exception payloads.
- No resolver tests for schema-plus-linter advisor ordering.
- No execution-state tests for output-schema journal/session metadata.
- No end-to-end integration tests covering schema validation before regex linting.

## Bug Reproduction / Failing Test First
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
- Arrange/Act/Assert outline:
  - Arrange a schema-enabled advisor with `maxRetries = 1` and a response chain that returns malformed JSON on the first call and valid schema-compliant JSON on the second.
  - Act by invoking `adviseCall(...)` with a simple request prompt.
  - Assert that the first retry appends a schema-specific corrective hint, the second response passes, and the final returned text is the exact raw JSON produced by the second response.
- Expected failure (pre-fix):
  - The test will fail because no output-schema advisor exists yet, no retry happens for invalid JSON, and no schema-specific hint is appended.

This is the best first failing test because it isolates the new runtime contract at the lowest practical level, mirrors the existing `LinterCallAdvisorTest` style, and demonstrates the core behavior change without needing full Spring wiring.

## Tests to Add/Update
### 1) `defaultsOutputSchemaMaxRetriesToTwoWhenSchemaIsPresent`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: `output_schema_max_retries` defaults to `2` whenever `output_schema` exists and the field is omitted.
- Fixtures/data: new valid YAML manifest fixture with a minimal root-object schema and no explicit retry count.
- Mocks: none; reuse `ApplicationContextRunner` pattern already used in catalog tests.

### 2) `failsStartupWhenOutputSchemaMaxRetriesIsPresentWithoutSchema`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: startup rejects `output_schema_max_retries` unless `output_schema` is also configured.
- Fixtures/data: invalid YAML manifest fixture containing only `output_schema_max_retries`.
- Mocks: none.

### 3) `failsStartupWhenOutputSchemaUsesUnsupportedKeyword`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: unsupported schema keywords such as `oneOf` or `$ref` fail startup with a field-specific error.
- Fixtures/data: invalid YAML fixture containing one unsupported keyword at a predictable path.
- Mocks: none.

### 4) `failsStartupWhenRootSchemaIsNotObject`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: root-array or non-object root schemas are rejected.
- Fixtures/data: invalid YAML manifest fixture with `output_schema.type: array`.
- Mocks: none.

### 5) `failsStartupWhenRequiredFieldIsMissingFromProperties`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: every `required` entry must exist in `properties`.
- Fixtures/data: invalid manifest fixture with `required: [vendorName]` but no `vendorName` property.
- Mocks: none.

### 6) `failsStartupWhenPropertiesDifferOnlyByCase`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: schema definitions containing property names that collapse case-insensitively are rejected during startup.
- Fixtures/data: invalid manifest fixture with `vendorName` and `VendorName`.
- Mocks: none.

### 7) `defaultsAdditionalPropertiesToFalseWhenOmitted`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: omitted `additionalProperties` is normalized to `false` during manifest loading/validation.
- Fixtures/data: valid manifest fixture omitting `additionalProperties`.
- Mocks: none.

### 8) `logsWarningForComplexButSupportedSchema`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: deep or large but supported schemas emit warnings and still load successfully.
- Fixtures/data: valid complex YAML fixture that crosses the configured warning constants.
- Mocks: capture logging if practical; otherwise assert successful load and cover warning behavior in a narrower validation test.

### 9) `returnsPassingResponseWithoutRetryWhenJsonMatchesSchema`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
- What it proves: schema-compliant JSON passes immediately and the advisor returns the raw JSON text unchanged.
- Fixtures/data: minimal schema manifest object and one valid JSON response.
- Mocks: use the same lightweight recording `CallAdvisorChain` style as `LinterCallAdvisorTest`.

### 10) `retriesWithCorrectiveHintAfterInvalidJson`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
- What it proves: malformed JSON triggers retry, appends an output-schema hint, and preserves the original user message between attempts.
- Fixtures/data: malformed JSON first response, valid JSON second response.
- Mocks: recording chain only.

### 11) `retriesWithCanonicalIssuesAfterSchemaMismatch`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
- What it proves: valid JSON with missing/unknown/wrong-type fields generates concise retry issues using canonical schema field names.
- Fixtures/data: schema with required fields plus a response containing missing field, unknown field, and wrong type.
- Mocks: recording chain only.

### 12) `rejectsAmbiguousCaseInsensitiveKeys`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
- What it proves: outputs containing both `vendorName` and `VendorName` fail as ambiguous even though a single case-variant key would be accepted.
- Fixtures/data: schema with one canonical property and an ambiguous JSON payload.
- Mocks: recording chain only.

### 13) `throwsBifrostOutputSchemaValidationExceptionWhenRetriesExhausted`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
- What it proves: exhausted parse/schema retries throw the new exception and expose `skillName`, `rawOutput`, `validationIssues`, `attemptCount`, `maxRetries`, and `failureMode`.
- Fixtures/data: consistently invalid response sequence.
- Mocks: recording chain only.

### 14) `recordsObservableOutcomeOnBoundSession`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/outputschema/OutputSchemaCallAdvisorTest.java`
- What it proves: output-schema retries and pass/fail outcomes are written to session state and the journal in the same style as current linter outcome tests.
- Fixtures/data: bound session runner and a retry-then-pass response sequence.
- Mocks: use `DefaultExecutionStateService` with a fixed clock, mirroring `LinterCallAdvisorTest`.

### 15) `createsOutputSchemaAdvisorBeforeLinterAdvisor`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SkillAdvisorResolverTests.java`
- What it proves: when both `output_schema` and `linter` are configured, the resolver returns both advisors in schema-first order.
- Fixtures/data: synthetic `YamlSkillDefinition` with both features configured.
- Mocks: none beyond the existing fixed `DefaultExecutionStateService`.

### 16) `storesLastOutputSchemaOutcomeAndJournalEntry`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/state/ExecutionStateServiceTest.java`
- What it proves: output-schema outcome recording updates the session's last outcome field and writes a journal entry with status, failure mode, attempt, and issue summary.
- Fixtures/data: synthetic `OutputSchemaOutcome` value.
- Mocks: none.

### 17) `retriesSchemaValidatedYamlSkillThroughAdvisorAndRecordsOutcome`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorOutputSchemaIntegrationTest.java`
- What it proves: the normal execution path retries malformed or schema-invalid responses through the advisor chain and records output-schema outcomes in session/journal state.
- Fixtures/data: stub catalog definition with `output_schema`, fake/advised `ChatClient`, and a two-response sequence.
- Mocks: follow the custom `AdvisedSequenceChatClient` pattern from `ExecutionCoordinatorLinterIntegrationTest`.

### 18) `runsRegexLinterOnlyAfterSchemaValidationPasses`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorOutputSchemaIntegrationTest.java`
- What it proves: schema validation happens first and regex linting only runs against a schema-valid raw response.
- Fixtures/data: definition with both `output_schema` and regex linter, plus a response sequence that first becomes schema-valid but still linter-invalid.
- Mocks: advised fake `ChatClient` that captures system prompts and call count.

### 19) `returnsOriginalJsonStringAfterSchemaValidationAndRegexLintingPass`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorOutputSchemaIntegrationTest.java`
- What it proves: success returns the exact model output string, not a reparsed or normalized JSON string.
- Fixtures/data: valid schema-compliant JSON with formatting or field ordering worth preserving.
- Mocks: advised fake `ChatClient`.

### 20) `attachesResolvedSchemaAdvisorsToSkillChatClient`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`
- What it proves: skill chat-client creation includes the resolved output-schema advisor stack when present.
- Fixtures/data: schema-enabled `YamlSkillDefinition`.
- Mocks: existing mocked `ChatClient.Builder` capture strategy in `SpringAiSkillChatClientFactoryTests`.

## How to Run
- Compile without tests: `mvn test -DskipTests`
- Catalog-focused tests: `mvn -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- Advisor resolver and advisor unit tests: `mvn -pl bifrost-spring-boot-starter -Dtest=SkillAdvisorResolverTests,OutputSchemaCallAdvisorTest test`
- Execution state tests: `mvn -pl bifrost-spring-boot-starter -Dtest=ExecutionStateServiceTest test`
- Integration tests for runtime wiring: `mvn -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorLinterIntegrationTest,ExecutionCoordinatorOutputSchemaIntegrationTest test`
- Full starter module regression sweep: `mvn -pl bifrost-spring-boot-starter test`
- Full repo sweep including sample manifests: `mvn test`

## Exit Criteria
- [x] A minimal failing advisor test exists and fails pre-fix for invalid JSON/schema retry behavior.
- [x] Catalog tests cover the new manifest contract, including retry defaults, unsupported keywords, root object enforcement, case-insensitive property uniqueness, and `additionalProperties` defaulting.
- [x] Advisor unit tests cover pass, retry, exhaustion, exception payload, canonical issue messages, and ambiguous key casing.
- [x] Resolver tests verify schema advisor creation and schema-before-linter ordering.
- [x] Execution-state tests verify output-schema outcomes are persisted with enough metadata to debug failures.
- [x] Integration tests prove schema validation runs through the normal execution path and composes correctly with regex linting.
- [x] Successful schema-validated responses are returned exactly as the model produced them.
- [x] Existing linter-focused tests still pass without behavioral regressions.
- [ ] Manual verification confirms startup warnings, retry messaging, and journal/session metadata are understandable in practice.
