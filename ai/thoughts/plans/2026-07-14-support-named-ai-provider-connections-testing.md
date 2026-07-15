# Named AI Provider Connections Testing Plan

## Change Summary

- Replace `AiProvider` with `AiDriver` and remove the provider-keyed configuration/resolver APIs.
- Consolidate `BifrostModelsProperties`, `BifrostSessionProperties`, and `BifrostSkillProperties` into one strict `BifrostProperties` root containing `session`, `skills`, `connections`, and `models`.
- Require every framework model alias to reference a named connection; reject the removed `models.*.provider` property.
- Construct and reuse one Bifrost-owned Spring AI `ChatModel` per connection through internal OpenAI, Anthropic, Gemini, and Ollama factories.
- Resolve models by complete `EffectiveSkillExecutionConfiguration`, while keeping provider model ID and thinking options request-scoped and driver-specific.
- Propagate framework model, connection, driver, and provider model through descriptors, direct/planning/step traces, errors, metrics, and the CLI.
- Treat the change as an atomic pre-release break. No legacy configuration, resolver overload, Spring AI singleton fallback, or trace compatibility alias remains.
- Add source-verified skill-authoring guidance proving that YAML skills select only framework model aliases and cannot select endpoints or credentials.

## Impacted Areas

### Configuration and startup

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostModelsProperties.java`
- `BifrostSessionProperties.java`, `BifrostSkillProperties.java`, `BifrostAutoConfiguration.java`, and every injection/call site of those types.
- New unified `BifrostProperties`, strict unknown-field binding, cross-reference validation, typed driver option blocks, and configuration metadata.
- `bifrost-spring-boot-starter/src/test/resources/application-test.yml` and sample `application.yml`.

### Client construction and routing

- `AiProvider`, `SkillChatModelResolver`, `DefaultSkillChatModelResolver`, `SkillChatOptionsAdapter`, and `SpringAiSkillChatClientFactory`.
- New named connection registry and OpenAI, Anthropic, Gemini, and Ollama connection factories.
- `YamlSkillCatalog`, `EffectiveSkillExecutionConfiguration`, and all test helpers constructing effective configuration directly.

### Execution and operational identity

- `SkillExecutionDescriptor`, `ModelTraceContext`, and new `ModelExecutionIdentity`.
- Direct mission, planning, step-loop, nested tool, attachment, and usage-recording paths.
- Micrometer model meters and `bifrost-cli` smart summaries/trace fixtures.

### Samples and authoring evidence

- Root README, sample README/configuration/tests, generated configuration metadata.
- New `ai/skill-authoring/model-selection-and-connections.md` plus the skill-authoring routing/coverage table.
- Focused tests must establish alias-only skill selection, thinking-level validation, application-owned connection routing, and non-overridable connection identity.

## Risk Assessment

### High risk

- **Strict root binding**: a root-level strict binder can accidentally reject valid `bifrost.session` or `bifrost.skills` fields unless every existing group is represented in the unified aggregate.
- **Secret leakage**: API keys and arbitrary static header values can escape through validation messages, property `toString`, construction exceptions, debug logs, traces, metric tags, or connection-object serialization.
- **Same-driver isolation**: two OpenAI or Ollama connection names must construct separate transports/models and never collapse back to an enum-keyed singleton.
- **Request path correctness**: Spring AI base URL and completions-path composition can produce a valid client that sends requests to the wrong path, especially for OpenAI-compatible gateways.
- **Lifecycle/reuse**: eager startup construction must happen once per connection, be thread-safe, and close only Bifrost-owned closeable resources.
- **Execution identity drift**: direct, planning, step, response, and failure metadata are currently assembled at separate call sites and can disagree.

### Medium risk

- **Gemini construction modes**: API-key and Vertex project/location/credentials modes use different Google SDK paths and need explicit, injectable test seams.
- **Driver field applicability**: permissive binding could silently accept OpenAI headers/options on Anthropic, Gemini, or Ollama connections.
- **Custom resolver backoff**: an application-provided resolver must prevent default client construction while configuration binding/validation remains deterministic.
- **Metric cardinality**: connection and driver are bounded tags; base URL, credentials, headers, and provider model must never become tags.
- **Destructive trace/CLI change**: Java trace producers, NDJSON fixtures, Go decoding, and smart summaries must change atomically.
- **Broad properties-type churn**: quota/session tests and many catalog/coordinator fixtures directly instantiate the old property types.

### Lower risk but required regression coverage

- Existing mapped YAML skills still load without a model or connection-backed chat client.
- Provider-model request options and thinking-level behavior remain unchanged after the driver rename.
- Attachments, structured responses, advisors, and tool callbacks continue using the chosen `ChatClient`.
- Sample context tests remain offline and do not attempt live provider calls.

## Existing Test Coverage

### Useful current coverage

- `BifrostModelsPropertiesTest#rejectsInvalidModelCatalogEntriesAtStartup` proves current Bean Validation failures through `ApplicationContextRunner`.
- `BifrostSessionPropertiesTest` protects session defaults, overrides, and quota validation; these cases must survive the move into nested `BifrostProperties.Session`.
- `BifrostAutoConfigurationTests` covers property bean creation, model catalog binding, concrete provider bean collection, effective descriptors, and conditional auto-configuration.
- `YamlSkillCatalogTests#defaultsThinkingLevelToMediumWhenModelSupportsThinking`, `#omitsThinkingLevelWhenSelectedModelHasNoThinkingSupport`, `#failsStartupWhenYamlSkillReferencesUnknownModel`, and `#failsStartupWhenThinkingLevelIsUnsupportedForModel` protect alias/thinking semantics.
- `DefaultSkillChatModelResolverTests` protects current resolver lookup and missing-model diagnostics.
- `SpringAiSkillChatClientFactoryTests` captures the selected model and request options, covering all four option adapters, GPT-5 temperature behavior, thinking settings, and advisor composition.
- `MissionExecutionEngineTest`, `PlanningServiceTest`, and `StepLoopMissionExecutionEngineTest` already inspect model calls and trace records in direct, planning, and step execution.
- `SpringAiMissionUserMessageSenderTest` covers attachment/media behavior and provider/model failure diagnostics.
- `ExecutionTraceContractTest` compares planning and mission model-event envelopes.
- `MicrometerUsageMetricsRecorderTest` covers existing model/tool/linter/guardrail meters.
- `SampleApplicationTests` proves the sample context and YAML catalog load without live calls.
- `NdjsonExecutionTraceReaderTest` protects generic Java trace reading. The Go CLI currently has no `_test.go` coverage.

### Gaps

- No named connection type, cross-reference validation, strict unified root, or unknown nested-property coverage exists.
- No test can represent two concrete clients with the same driver.
- No driver factory or client lifecycle/construction-count coverage exists.
- No mock HTTP server dependency or request-path/header capture fixture exists.
- No test proves OpenAI and an OpenAI-compatible gateway coexist.
- No trace, metric, descriptor, or CLI contract contains connection/framework model identity.
- No comprehensive secret-sentinel test searches every Bifrost-owned output surface.
- No test proves connection selection is unaffected by skill input.
- No source-verified model-selection/connection authoring topic exists.

## Bug Reproduction / Failing Test First

This is a new behavior plus a destructive API correction, not a pure refactor. Use two small red tests before production implementation.

### First red test: unified strict configuration

- **Name**: `bindsKnownUnifiedRootAndRejectsUnknownConnectionFields`
- **Type**: Integration (`ApplicationContextRunner` configuration binding).
- **Location**: Rename/create `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostPropertiesTest.java`.
- **Arrange**:
  - Add the minimum test-side references for the expected `BifrostProperties` contract.
  - Configure valid `bifrost.session.max-depth`, `bifrost.skills.locations`, two `bifrost.connections` entries using `driver=openai`, and two model aliases.
  - In a second runner, add `bifrost.connections.primary.unknown-transport-field=value`.
- **Act**: Start each context and obtain the unified properties bean.
- **Assert**:
  - The valid context starts and preserves session/skills values plus both connections/models.
  - The invalid context fails with the exact unknown configuration path.
- **Expected failure pre-fix**: The expected unified type/connection fields do not exist; after adding only compilation scaffolding, current permissive root binding either ignores connection/unknown fields or fails because models still require `provider`.

### Second red test: same-driver connection identity

- **Name**: `resolvesDistinctModelsForTwoConnectionsUsingSameDriver`
- **Type**: Unit.
- **Location**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/DefaultSkillChatModelResolverTests.java`.
- **Arrange**: Register distinct mock `ChatModel` objects as `openai-main` and `openrouter`, both with `AiDriver.OPENAI`; create two effective configurations differing only in connection and provider model.
- **Act**: Resolve both configurations.
- **Assert**: Each configuration returns the model registered under its connection name.
- **Expected failure pre-fix**: The current API accepts only `AiProvider` and stores an enum map, so it cannot represent or distinguish the two connections.

Do not begin protocol integration tests until these two contract tests are red for the intended reasons and the old provider-only assertions have been deliberately replaced.

## Tests to Add or Update

### 1. Unified root preserves every known Bifrost property group

- **Name**: `bindsSessionSkillsConnectionsAndModelsUnderStrictRoot`
- **Type**: Integration.
- **Location**: `autoconfigure/BifrostPropertiesTest.java`.
- **What it proves**: Consolidation does not lose session defaults/overrides or skill locations while adding connections/models.
- **Fixtures/data**: Inline property values covering every nested group and the current quota/attachment defaults.
- **Mocks**: `ApplicationContextRunner`; no provider clients.

### 2. Strict binding rejects unknown properties everywhere

- **Name**: `rejectsUnknownPropertyInEveryBifrostSection`
- **Type**: Parameterized integration.
- **Location**: `autoconfigure/BifrostPropertiesTest.java`.
- **What it proves**: Unknown fields fail under `session`, `skills`, a dynamic connection entry, a driver option block, and a model entry; valid sibling sections are not misclassified.
- **Fixtures/data**: One invalid path per argument; assert the complete kebab-case path.
- **Mocks**: `ApplicationContextRunner`.

### 3. Removed provider configuration fails destructively

- **Name**: `rejectsRemovedModelProviderProperty`
- **Type**: Integration.
- **Location**: `autoconfigure/BifrostPropertiesTest.java`.
- **What it proves**: `bifrost.models.fast.provider=openai` is unknown and no deprecation/fallback behavior remains.
- **Fixtures/data**: A otherwise-valid connection/model plus the removed field.
- **Mocks**: `ApplicationContextRunner`; captured startup failure.

### 4. Model/connection graph validation is complete

- **Names**:
  - `rejectsBlankConnectionAndModelNames`
  - `rejectsUnknownModelConnectionReference`
  - `rejectsMissingDriverConnectionAndProviderModel`
  - `acceptsMultipleAliasesSharingOneConnection`
- **Type**: Unit plus binding integration.
- **Location**: `autoconfigure/BifrostPropertiesTest.java` and `skill/YamlSkillCatalogTests.java`.
- **What it proves**: Map keys and required fields are nonblank, references resolve at startup, duplicate drivers are legal, and shared aliases retain different provider model IDs.
- **Fixtures/data**: Programmatic objects for blank map keys where property syntax cannot express them; inline properties/YAML for normal binding.
- **Mocks**: No network; catalog `Resource` fixtures.

### 5. Driver-specific validation and field applicability

- **Names**:
  - `validatesRequiredFieldsForEachDriver`
  - `rejectsDriverOptionBlockForDifferentDriver`
  - `rejectsHeadersForNonOpenAiDriver`
  - `rejectsInvalidOrNullHeaderValues`
  - `validatesGeminiApiKeyAndVertexModes`
- **Type**: Parameterized unit/integration.
- **Location**: `autoconfigure/BifrostPropertiesTest.java`.
- **What it proves**: OpenAI/Anthropic credentials, Ollama base URL, and Gemini's exclusive complete modes are enforced; unsupported typed fields do not disappear silently.
- **Fixtures/data**: One valid and invalid connection per driver, RFC-invalid header names, incomplete Vertex tuples.
- **Mocks**: None.

### 6. Placeholder resolution is redacted

- **Name**: `resolvesCredentialPlaceholdersWithoutLeakingValuesOnFailure`
- **Type**: Integration/security.
- **Location**: `autoconfigure/BifrostPropertiesTest.java`.
- **What it proves**: Property placeholders work, but startup exceptions and captured logs omit API-key/header/credential sentinels.
- **Fixtures/data**: `TEST_API_KEY_SENTINEL_7f3a`, `TEST_HEADER_SENTINEL_91bc`, and `TEST_CREDENTIAL_SENTINEL_24de` supplied through property values.
- **Mocks**: `CapturedOutput`; a factory test double that fails with a safe field-path exception.

### 7. Configuration metadata exposes the new contract

- **Name**: `publishesUnifiedConnectionAndModelConfigurationMetadata`
- **Type**: Resource/contract test.
- **Location**: New `autoconfigure/BifrostConfigurationMetadataTest.java`.
- **What it proves**: Metadata describes connections, models, driver hints, sensitive placeholder guidance, typed blocks, and retained session/skills paths.
- **Fixtures/data**: Generated/additional metadata JSON loaded from the test classpath.
- **Mocks**: Jackson only.

### 8. Each driver factory maps only its supported properties

- **Names**:
  - `OpenAiConnectionChatModelFactoryTests#mapsEndpointCredentialsHeadersAndPaths`
  - `AnthropicConnectionChatModelFactoryTests#mapsEndpointCredentialsAndVersionPaths`
  - `GeminiConnectionChatModelFactoryTests#buildsApiKeyMode`
  - `GeminiConnectionChatModelFactoryTests#buildsVertexModeWithOptionalCredentials`
  - `OllamaConnectionChatModelFactoryTests#buildsNativeOllamaModel`
- **Type**: Unit.
- **Location**: New tests under `chat/connection`.
- **What it proves**: Exact Spring AI 1.1.6 builder inputs, native Ollama usage, and deterministic Gemini mode selection.
- **Fixtures/data**: Typed connection properties with nonsecret dummy values.
- **Mocks**: Injectable API/client builder seams; Mockito captors. Never reflect into private SDK fields.

### 9. OpenAI base URL, path, auth, and headers reach the wire

- **Name**: `sendsOpenAiRequestToConfiguredConnectionPathWithSensitiveHeaders`
- **Type**: Integration using MockWebServer.
- **Location**: New `chat/connection/OpenAiConnectionProtocolTest.java`.
- **What it proves**: Base URL plus `chat-completions-path` compose correctly; bearer token, organization/project, and static headers are sent; request model comes from request options.
- **Fixtures/data**: Local server, queued minimal chat response, header sentinels, model `gpt-test-one`.
- **Mocks**: MockWebServer only; real Spring AI OpenAI API/model.

### 10. OpenAI-compatible gateway remains an OpenAI driver

- **Name**: `routesNativeOpenAiAndCompatibleGatewayAsDistinctOpenAiConnections`
- **Type**: Integration using two MockWebServers.
- **Location**: `chat/connection/OpenAiConnectionProtocolTest.java`.
- **What it proves**: Two OpenAI-driver names use distinct endpoints/auth/headers without an `OPENROUTER` enum and without transport collapse.
- **Fixtures/data**: `openai-main` and `openrouter`, distinct response markers and provider model IDs.
- **Mocks**: Two MockWebServer instances.

### 11. Native Anthropic and Ollama request shapes remain intact

- **Names**:
  - `AnthropicConnectionProtocolTest#sendsRequestToConfiguredAnthropicConnection`
  - `OllamaConnectionProtocolTest#routesTwoNativeOllamaConnectionsIndependently`
- **Type**: Integration using MockWebServer.
- **Location**: New tests under `chat/connection`.
- **What it proves**: Real native clients target the correct endpoints and preserve provider-specific request semantics; two Ollama servers do not use OpenAI compatibility accidentally.
- **Fixtures/data**: Minimal protocol-valid responses for Spring AI 1.1.6 and distinct request model IDs.
- **Mocks**: MockWebServer; no live services.

### 12. Registry construction, reuse, and ownership

- **Names**:
  - `buildsOneModelPerConnectionAtStartup`
  - `buildsDistinctModelsForSameDriverConnections`
  - `reusesModelForAliasesSharingConnection`
  - `closesOnlyBifrostOwnedCloseableResources`
- **Type**: Unit.
- **Location**: New package-private auto-configuration infrastructure test `autoconfigure/NamedAiConnectionRegistryTests.java`.
- **What it proves**: Construction count, immutable lookup, identity separation, alias reuse, and cleanup ownership.
- **Fixtures/data**: Counting factory test doubles and closeable/non-closeable model doubles.
- **Mocks**: Mockito or small fakes; no Spring context.

### 13. Custom resolver disables default construction

- **Name**: `backsOffRegistryAndDefaultResolverWhenApplicationProvidesResolver`
- **Type**: Auto-configuration integration.
- **Location**: `autoconfigure/BifrostAutoConfigurationTests.java`.
- **What it proves**: `@ConditionalOnMissingBean` remains the supported override and no default provider/connection client is constructed.
- **Fixtures/data**: User configuration defining the new resolver signature and otherwise-valid unified properties.
- **Mocks**: Mock resolver plus a factory bean that would fail if invoked.

### 14. Resolver uses complete effective configuration

- **Names**:
  - `resolvesDistinctModelsForTwoConnectionsUsingSameDriver`
  - `reportsSkillAliasConnectionAndDriverWhenConnectionIsUnavailable`
- **Type**: Unit.
- **Location**: `chat/DefaultSkillChatModelResolverTests.java`.
- **What it proves**: Connection is the lookup key and diagnostics contain only safe identity.
- **Fixtures/data**: Two OpenAI effective configurations and immutable registry map.
- **Mocks**: Mock `ChatModel` values.

### 15. Request options remain driver-specific and per request

- **Names**:
  - `passesCompleteConfigurationToResolver`
  - `appliesDifferentProviderModelsForAliasesSharingConnection`
  - `dispatchesToEveryDriverAdapter`
  - Existing GPT-5/thinking/advisor tests renamed from provider to driver terminology.
- **Type**: Unit.
- **Location**: `chat/SpringAiSkillChatClientFactoryTests.java`.
- **What it proves**: Registry reuse does not freeze provider model or thinking options into the connection client.
- **Fixtures/data**: Recording builder factory and effective configurations for all four drivers.
- **Mocks**: Existing `ChatClient.Builder`/resolver harness.

### 16. Catalog produces safe effective identity

- **Names**:
  - `resolvesModelAliasToConnectionDriverAndProviderModel`
  - `preservesThinkingLevelValidationAcrossSharedConnection`
  - `doesNotRetainConnectionSecretsInSkillDefinition`
- **Type**: Unit/integration.
- **Location**: `skill/YamlSkillCatalogTests.java` and `skill/YamlSkillDefinitionTest.java`.
- **What it proves**: Effective configuration has exactly framework model, connection, driver, provider model, and thinking level; no properties object or credential is reachable.
- **Fixtures/data**: YAML skill resources and unified properties with secret sentinels.
- **Mocks**: None.

### 17. Skill input cannot change routing identity

- **Name**: `skillInputCannotOverrideConfiguredConnection`
- **Type**: Execution integration.
- **Location**: `core/ExecutionCoordinatorIntegrationTest.java`.
- **What it proves**: Even if canonical business input contains keys named `connection`, `baseUrl`, `apiKey`, or `headers`, resolver input remains the catalog-derived effective configuration.
- **Fixtures/data**: A schema allowing those ordinary keys plus a recording resolver; verify values remain business input only.
- **Mocks**: Recording resolver/client; no provider server.

### 18. Direct, planning, step, nested, tool, and attachment flows route correctly

- **Names**:
  - `MissionExecutionEngineTest#usesSelectedConnectionForDirectModelCall`
  - `PlanningServiceTest#usesSelectedConnectionForPlanningCall`
  - `StepLoopMissionExecutionEngineTest#retainsConnectionAcrossEveryStep`
  - `ExecutionCoordinatorIntegrationTest#nestedSkillResolvesItsOwnConnection`
  - `ToolCallbackFactoryTest#toolCallRemainsOnSelectedNativeClient`
  - `SpringAiMissionUserMessageSenderTest#attachmentFailureNamesConnectionWithoutSecrets`
- **Type**: Unit/integration using existing runtime fakes.
- **Location**: Existing test classes.
- **What it proves**: Every execution mode uses the selected definition's connection; nested children do not inherit the parent's endpoint; tools/media remain intact.
- **Fixtures/data**: Parent and child definitions using distinct same-driver connections, recording chat clients, attachment resources.
- **Mocks**: Existing fake planning/model responses and Mockito collaborators.

### 19. One identity contract covers success and failure traces

- **Names**:
  - Extend `ExecutionTraceContractTest#modelEventsAreSemanticallyEquivalentAcrossPlanningAndMission`.
  - Add `modelIdentityIsConsistentAcrossPreparedSentResponseAndFailureRecords`.
- **Type**: Contract/integration.
- **Location**: `runtime/trace/ExecutionTraceContractTest.java` plus mission/planning/step tests.
- **What it proves**: `frameworkModel`, `connection`, `driver`, and `providerModel` are identical on enclosing frames and all model events, including failures; removed `provider` is absent.
- **Fixtures/data**: Fixed effective identity and forced model exception.
- **Mocks**: Existing in-memory trace handle/state service.

### 20. Trace serialization and journals never contain connection configuration

- **Names**:
  - `NdjsonTraceRecordWriterTest#writesOnlySafeModelIdentity`
  - `ExecutionJournalProjectionContractTest#doesNotProjectConnectionProperties`
- **Type**: Unit/contract.
- **Location**: Existing runtime trace tests.
- **What it proves**: Only names/driver/provider model are serialized; credentials, headers, base URL, and property objects are absent.
- **Fixtures/data**: Secret-sentinel connection plus serialized NDJSON/journal snapshot.
- **Mocks**: In-memory writer/projector.

### 21. Metrics use bounded identity tags

- **Names**:
  - Extend `MicrometerUsageMetricsRecorderTest#emitsMicrometerMetersForModelToolLinterAndGuardrailEvents`.
  - Add `modelMetersTagConnectionAndDriverButNotEndpointOrProviderModel`.
- **Type**: Unit.
- **Location**: `runtime/usage/MicrometerUsageMetricsRecorderTest.java` and `SessionUsageServiceTest.java`.
- **What it proves**: Model counters carry normalized connection/driver tags while quotas/accounting remain unchanged and sensitive/high-cardinality fields are absent.
- **Fixtures/data**: `SimpleMeterRegistry`, effective identity, usage record.
- **Mocks**: Recording `UsageMetricsRecorder` for service propagation.

### 22. CLI renders the new identity

- **Names**:
  - `TestSmartInfoFormatsNamedConnectionIdentity`
  - `TestTraceDetailRetainsArbitraryMetadata`
- **Type**: Go unit.
- **Location**: New `bifrost-cli/main_test.go`.
- **What it proves**: Smart summary renders `frameworkModel -> connection (driver/providerModel)` and updated NDJSON remains inspectable.
- **Fixtures/data**: Minimal in-memory old-development/new records are unnecessary; use only the new unreleased contract and update checked-in fixtures atomically.
- **Mocks**: None.

### 23. Comprehensive redaction regression

- **Name**: `connectionSecretsNeverAppearInBifrostOwnedOutput`
- **Type**: Cross-component integration/security.
- **Location**: New `autoconfigure/ConnectionSecretRedactionIntegrationTest.java`.
- **What it proves**: API key, every custom header value (including non-Authorization names), credential sentinel, and base URL are absent from captured logs, exceptions, property strings, descriptors, traces, journals, and metric tags.
- **Fixtures/data**: Unique non-overlapping sentinels; force validation and protocol failures separately.
- **Mocks**: Captured logs, in-memory trace persistence, `SimpleMeterRegistry`, MockWebServer.

### 24. Sample and authoring evidence remain executable

- **Names**:
  - Update `SampleApplicationTests#loadsBifrostAutoConfiguration` to assert unified properties.
  - Add `sampleDeclaresSharedOpenAiAndDistinctOllamaConnections`.
  - Add/rename catalog test `yamlSkillSelectsOnlyFrameworkModelAlias`.
- **Type**: Sample context plus framework contract.
- **Location**: `bifrost-sample/src/test/.../SampleApplicationTests.java` and `skill/YamlSkillCatalogTests.java`.
- **What it proves**: Samples use named connections exclusively; skills still declare only `model`; thinking validation and application-owned connection routing support the new skill-authoring topic.
- **Fixtures/data**: Converted sample `application.yml`; no live provider response.
- **Mocks**: Dummy placeholder values; use the real default registry because construction must remain offline and must not issue provider calls during context load.

## Test Fixtures and Data Rules

- Use MockWebServer on ephemeral loopback ports; never bind fixed ports or require Docker.
- Queue the smallest protocol-valid Spring AI 1.1.6 response for each native driver and assert captured method/path/body/headers.
- Give every connection and response a unique marker so cross-routing cannot pass accidentally.
- Use distinct secret sentinels and assert their absence from complete captured outputs, not only selected fields.
- Never put real API keys in source, environment requirements, snapshots, or failure output.
- Prefer small builders for `EffectiveSkillExecutionConfiguration` and unified properties in tests to avoid repeating the new five-field constructor throughout the suite.
- Keep protocol tests separate from runtime-routing tests: protocol tests use real Spring AI clients; runtime tests use recording clients/resolvers for deterministic flow assertions.

## Test Implementation Order

1. Run the current starter/sample/CLI suites and record a clean baseline.
2. Add the two red contract tests for unified strict binding and same-driver resolution.
3. Implement/migrate the unified properties root and configuration validation until configuration tests pass.
4. Add factory and registry unit tests, then protocol tests for OpenAI, Anthropic, and Ollama plus Gemini builder seams.
5. Replace resolver/effective configuration/options APIs and restore focused chat/catalog tests.
6. Update direct/planning/step/nested/tool/attachment tests.
7. Centralize identity and update trace, journal, metric, redaction, and CLI tests.
8. Convert sample/configuration metadata and establish the focused tests cited by skill-authoring documentation.
9. Run module, reactor, and Go suites; then complete optional live smoke verification.

## How to Run

### Baseline before implementation

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter test
.\mvnw.cmd -pl bifrost-sample test
Push-Location bifrost-cli
go test ./...
Pop-Location
```

### First red tests

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostPropertiesTest,DefaultSkillChatModelResolverTests test
```

Record the expected failures described above. A failure caused only by missing real credentials, occupied ports, timing, or unrelated context startup is not an acceptable red test.

### Configuration and catalog

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostPropertiesTest,BifrostConfigurationMetadataTest,BifrostAutoConfigurationTests,YamlSkillCatalogTests test
```

### Factories, registry, and request routing

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=*ConnectionChatModelFactoryTests,*ConnectionProtocolTest,NamedAiConnectionRegistryTests,DefaultSkillChatModelResolverTests,SpringAiSkillChatClientFactoryTests test
```

### Runtime, trace, metrics, and security

```powershell
.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=MissionExecutionEngineTest,PlanningServiceTest,StepLoopMissionExecutionEngineTest,ExecutionCoordinatorIntegrationTest,ToolCallbackFactoryTest,SpringAiMissionUserMessageSenderTest,ExecutionTraceContractTest,NdjsonTraceRecordWriterTest,ExecutionJournalProjectionContractTest,MicrometerUsageMetricsRecorderTest,SessionUsageServiceTest,ConnectionSecretRedactionIntegrationTest test
```

### Full verification

```powershell
.\mvnw.cmd verify
Push-Location bifrost-cli
go test ./...
Pop-Location
```

No environment variables, cloud credentials, Docker services, OpenAI/OpenRouter accounts, or Ollama processes are required for automated tests.

### Optional manual smoke environment

- `OPENAI_API_KEY` only when manually testing native OpenAI.
- `OPENROUTER_API_KEY`, `OPENROUTER_SITE_URL`, and `OPENROUTER_APP_NAME` only when manually testing OpenRouter.
- Two explicitly configured Ollama base URLs when manually verifying multi-server routing.
- Manual provider calls are not CI exit criteria; record skipped infrastructure-dependent checks explicitly.

## Exit Criteria

- [x] The current baseline suites pass before production changes, or unrelated pre-existing failures are recorded separately.
- [x] Both failing-test-first cases exist and fail for the expected missing strict-root/connection-routing behavior before implementation.
- [ ] One strict `BifrostProperties` root accepts all known session/skills/connections/models fields and rejects unknown fields in each section. (Core root/connection/model cases exist; the per-section matrix remains to be added.)
- [x] Existing session defaults, quotas, attachments, and skill-location behavior remain protected after property-class consolidation.
- [x] Removed `models.*.provider`, `AiProvider`, provider-keyed resolver calls, and implicit Spring AI singleton fallback have no compatibility path.
- [ ] All four driver configurations bind and construct through focused tests; Gemini API-key and Vertex modes are separately covered. (Construction is covered; complete binding coverage remains.)
- [x] Two same-driver connection names produce distinct clients; aliases sharing a connection reuse exactly one client.
- [x] OpenAI-compatible path/header/auth behavior is verified at the wire without adding a vendor-specific driver.
- [ ] Native Ollama and Anthropic request behavior is verified with local mock servers; no live services are used in CI. (One native request per driver is covered; the planned two-Ollama-server protocol case remains.)
- [x] Provider model IDs and thinking options remain request-scoped and driver-specific.
- [ ] Direct, planning, every step, nested skill, tool, structured response, and attachment flows route through the expected connection. (Core direct/planning/step identity is covered; the full matrix remains.)
- [ ] Skill input cannot override catalog-derived connection identity. (The implementation is configuration-derived, but the planned adversarial test remains.)
- [ ] Trace frames/events, descriptors, errors, metrics, and CLI contain consistent framework model/connection/driver/provider-model identity. (Success identity and planning/mission failure traces are covered; the full failure-path matrix remains.)
- [ ] API keys, credential values, static header values, base URLs, and full connection objects do not appear in any Bifrost-owned logs, exceptions, strings, traces, journals, or metric tags. (Focused owned-output checks now cover runtime provider-failure traces; journals, Actuator, exception chains, and the complete matrix remain.)
- [x] Connection/driver metric tags are bounded; base URL and provider model are absent from tags.
- [x] Updated sample context loads offline and contains no removed provider-based model entries.
- [x] Tests cited as evidence for `model-selection-and-connections.md` establish alias-only skill selection, thinking validation, application-owned connection resolution, and non-overridable connection identity.
- [x] Generated/additional configuration metadata accurately covers the unified root and dynamic connection/model entries.
- [x] Starter, sample, full Maven reactor, and Go CLI test commands pass.
- [x] Generated metadata content is verified automatically for connection fields, driver hints, and removal of provider.
- [ ] Optional live smoke results are recorded when infrastructure is available; their absence does not block automated completion.

## References

- Ticket: `ai/thoughts/tickets/eng-support-named-ai-provider-connections.md`
- Implementation plan: `ai/thoughts/plans/2026-07-14-support-named-ai-provider-connections.md`
- Testing-plan command: `ai/commands/3_testing_plan.md`
- Skill-authoring routing: `ai/skill-authoring/README.md`
- Skill-authoring source verification: `ai/skill-authoring/source-verification.md`
