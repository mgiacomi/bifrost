# Named AI Provider Connections Implementation Plan

## Overview

Replace Bifrost's provider-keyed chat-model routing with an explicit named-connection catalog. Each framework model alias will select one application-owned connection, each connection will select an `AiDriver`, and Bifrost will construct and reuse one Spring AI `ChatModel` per connection while continuing to apply the provider model ID and thinking options per request.

This is an intentional pre-release breaking change. The existing `models.*.provider` property, `AiProvider` type, provider-keyed resolver API, implicit Spring AI singleton lookup, and old trace identity will be removed rather than deprecated.

## Current State Analysis

- `BifrostModelsProperties` binds only `bifrost.models`; every entry requires `provider` and `provider-model`, and there is no connection catalog (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostModelsProperties.java:14`).
- Bifrost configuration is split across a root `bifrost` model class plus separate `bifrost.session` and `bifrost.skills` classes. Making the current root class strict would incorrectly treat valid sibling sections as unknown, so strict binding requires one unified root aggregate.
- `YamlSkillCatalog` resolves a skill's model alias and creates an `EffectiveSkillExecutionConfiguration` containing framework model, provider, provider model, and thinking level (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:148`).
- `DefaultSkillChatModelResolver` stores one `ChatModel` per `AiProvider`, so two endpoints using the same driver cannot coexist (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillChatModelResolver.java:10`).
- `BifrostAutoConfiguration` discovers at most one auto-configured concrete Spring AI model bean for each provider and builds the provider map (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:399`).
- `SpringAiSkillChatClientFactory` already applies `provider-model` and thinking behavior through one options adapter per provider; this per-request behavior should remain driver-keyed (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:74`).
- Direct execution, planning, and step execution each build trace identity manually from provider and provider model (`DefaultMissionExecutionEngine.java:148`, `DefaultPlanningService.java:138`, and `StepLoopMissionExecutionEngine.java:555`).
- Attachment requests and nested tools already use the `ChatClient` chosen for the executing YAML definition. Nested YAML skills enter `ExecutionCoordinator` again and therefore should resolve their own model alias and connection rather than inheriting the parent's connection (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:113`).
- The CLI's smart summary explicitly formats `provider/providerModel`, although generic metadata decoding already tolerates additional keys (`bifrost-cli/main.go:848`).
- Property tests use `ApplicationContextRunner`, resolver/client tests use Mockito and a recording builder, and no mock HTTP server dependency exists yet (`BifrostModelsPropertiesTest.java:13`, `DefaultSkillChatModelResolverTests.java:13`, and `SpringAiSkillChatClientFactoryTests.java:39`).
- The project is `0.1.0-SNAPSHOT`, has no release tags, and the ticket now records that there are no deployments or released compatibility contract to preserve (`pom.xml:9`).

## Desired End State

An application can declare multiple named connections for any supported driver, including multiple OpenAI accounts/gateways and multiple Ollama servers. Multiple framework model aliases can reuse one connection while selecting different provider model IDs. Startup validates the entire connection/model graph and eagerly constructs exactly one reusable `ChatModel` for each configured connection used by the default resolver.

The normalized runtime identity is:

```text
framework model alias
    -> connection name
        -> AiDriver
        -> reusable Spring AI ChatModel
    -> provider model ID + effective thinking level
```

Every model call, descriptor, error, trace, and model-usage metric carries the nonsecret framework model/connection/driver/provider-model identity appropriate to that surface. No credential, static header value, base URL, or connection property object enters skill input, journals, traces, metrics, or diagnostics.

Representative configuration:

```yaml
bifrost:
  connections:
    openai-main:
      driver: openai
      api-key: ${OPENAI_API_KEY}
      openai:
        organization-id: ${OPENAI_ORG_ID:}
        project-id: ${OPENAI_PROJECT_ID:}

    openrouter:
      driver: openai
      base-url: https://openrouter.ai/api/v1
      api-key: ${OPENROUTER_API_KEY}
      headers:
        HTTP-Referer: ${OPENROUTER_SITE_URL}
        X-Title: ${OPENROUTER_APP_NAME:bifrost}

    ollama-east:
      driver: ollama
      base-url: http://ollama-east.internal:11434

  models:
    fast:
      connection: openai-main
      provider-model: gpt-4o-mini
    orchestrator:
      connection: openai-main
      provider-model: gpt-5
      thinking-levels: [low, medium, high]
    routed-sonnet:
      connection: openrouter
      provider-model: anthropic/claude-sonnet-4
```

### Key Discoveries

- Spring AI is pinned to 1.1.6 and exposes direct builders for `OpenAiApi`/`OpenAiChatModel`, `AnthropicApi`/`AnthropicChatModel`, `OllamaApi`/`OllamaChatModel`, and Google GenAI `Client`/`GoogleGenAiChatModel` (`pom.xml:44`).
- OpenAI's API builder directly supports static headers; Anthropic, Ollama, and Gemini require lower-level transport customization. V1 will therefore expose `headers` only for `driver: openai` and reject it for other drivers.
- `@ConfigurationProperties` currently ignores unknown properties by default, while Bifrost classes share overlapping `bifrost` prefixes. Consolidate them into one strict root before adding explicit cross-field/applicability validation.
- The existing `@ConditionalOnMissingBean(SkillChatModelResolver.class)` boundary is the supported application override and can remain even though the resolver method changes destructively (`BifrostAutoConfiguration.java:399`).
- Trace envelopes use an open metadata map, so the unreleased schema can switch from `provider` to `driver` and add connection/framework model without changing its structural representation (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/TraceRecord.java:13`).
- The skill-authoring README marks model selection and thinking levels as not yet documented. This work establishes enough executable behavior to add a focused, source-verified topic (`ai/skill-authoring/README.md:59`).

## What We're NOT Doing

- Preserving or deprecating `bifrost.models.*.provider`.
- Retaining an `AiProvider` alias, provider-keyed resolver overload, auto-configured singleton fallback, or old trace metadata solely for compatibility.
- Merging named connections with `spring.ai.*` configuration.
- Adding runtime connection selection, failover, load balancing, discovery, health routing, or cost routing.
- Adding OpenRouter, vLLM, LM Studio, or other OpenAI-compatible services as drivers.
- Exposing timeout, retry, proxy, insecure TLS, or arbitrary transport-builder configuration in v1.
- Supporting static custom headers for non-OpenAI drivers in v1.
- Making connection factories a public third-party driver SPI.
- Adding embeddings, image generation, speech, or other non-chat client types.
- Guaranteeing that an OpenAI-compatible endpoint supports tools, attachments, structured output, usage, or reasoning fields.

## Skill-Authoring Documentation Impact

**Impact**: Affected

- **Rationale**: A YAML skill still selects only a framework model alias, but the application-owned meaning of that alias, thinking-level validation, diagnostics, and the prohibition on skill/input-controlled connection selection are author-facing model-selection semantics.
- **Documents to update**: Create `ai/skill-authoring/model-selection-and-connections.md`; update routing and the “Model selection and thinking levels” coverage row in `ai/skill-authoring/README.md`.
- **Supporting evidence**: `BifrostModelsPropertiesTest`, `YamlSkillCatalogTests`, connection registry/factory tests, `SpringAiSkillChatClientFactoryTests`, execution/trace contract tests, invalid fixtures, and the converted sample `application.yml`.
- **Coverage table update**: Required. Change the topic from “Not yet documented” to “Initial, source-verified” and route model-selection/configuration questions to the new document.
- **LLM-first usability**: The new topic will lead with the separation among skill `model`, framework model alias, connection, driver, and provider model; use one compact decision table and minimal examples; mark connection configuration as application-owned; state enforced rules, recommendations, compatibility limitations, and implementation/test anchors without duplicating the root README.

## Implementation Approach

Use one strict root `BifrostProperties` aggregate for session, skills, named connections, and model aliases; one internal factory per supported driver; and one immutable registry keyed by connection name. Construct the registry once during auto-configuration, then pass the complete `EffectiveSkillExecutionConfiguration` to the resolver. Continue choosing `SkillChatOptionsAdapter` by `AiDriver` so provider model and thinking settings remain request-level concerns.

Make model identity a single value object/helper derived from effective configuration and reuse it when writing enclosing frame metadata, model trace records, descriptors, errors, and metric tags. This removes the current duplicated metadata maps and prevents direct/planning/step flows from drifting.

Before implementation, run `ai/commands/3_testing_plan.md` against this plan to produce the dedicated failing-test-first matrix and exit criteria.

## Phase 1: Establish the Driver and Strict Configuration Contract

### Overview

Replace provider terminology and define the complete connection/model configuration graph before constructing clients.

### Changes Required

#### 1. Rename the internal driver type

**Files**:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/AiProvider.java`
- All production and test references returned by `rg "AiProvider"`

**Changes**:

- Rename `AiProvider` to `AiDriver` without a compatibility alias.
- Preserve the four values `OPENAI`, `ANTHROPIC`, `GEMINI`, and `OLLAMA`.
- Update options-adapter keys, descriptors, tests, and user-facing diagnostics to say driver.

#### 2. Consolidate one strict `bifrost.*` properties aggregate

**Files**:

- Rename `BifrostModelsProperties.java` to `BifrostProperties.java`.
- Remove `BifrostSessionProperties.java` and `BifrostSkillProperties.java` after moving their nested values/defaults into the unified aggregate.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- New validator classes under `com.lokiscale.bifrost.autoconfigure`.

**Changes**:

- Bind one `@ConfigurationProperties(prefix = "bifrost", ignoreUnknownFields = false)` root containing `session`, `skills`, `connections`, and `models`; keep `execution-trace.*` separate.
- Preserve all current session/skills defaults, validation, and kebab-case paths while changing Java injection sites to read the corresponding nested group.
- Validate nonblank map keys and non-null values.
- Define `ModelCatalogEntry(connection, providerModel, thinkingLevels)`; remove `provider` entirely.
- Define `ConnectionProperties` with common `driver`, optional `baseUrl`, optional `apiKey`, and OpenAI-only sensitive `headers`.
- Add typed nested option groups:
  - OpenAI: `organization-id`, `project-id`, `chat-completions-path`.
  - Anthropic: `completions-path`, `version`, `beta-version`.
  - Gemini: API-key mode or Vertex mode using `vertex-ai`, `project-id`, `location`, and optional `credentials-uri`.
  - Ollama: no extra v1 fields beyond its required base URL.
- Treat OpenAI/Anthropic standard service URLs as driver defaults, but require credentials; require Ollama `base-url`; require either Gemini API key or the complete Vertex tuple.
- Reject a nonmatching typed option group and reject `headers` for non-OpenAI drivers.
- Validate header names as HTTP tokens and values as non-null while treating every value as sensitive.
- Validate every model reference against the connection map and report full paths such as `bifrost.models.fast.connection`.
- Ensure no property type renders secrets or full headers through `toString`.

#### 3. Add IDE configuration metadata

**Files**:

- New `bifrost-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`
- Property Javadocs in `BifrostProperties.java`

**Changes**:

- Describe `connections`, `driver`, `connection`, credential placeholders, driver applicability, and the absence of `spring.ai.*` inheritance.
- Add driver value hints and dynamic-map value metadata that the configuration processor cannot infer adequately.
- Verify generated metadata rather than committing generated `target` output.

#### 4. Convert property/catalog tests first

**Files**:

- `BifrostModelsPropertiesTest.java` (rename to `BifrostPropertiesTest.java`)
- `YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/resources/application-test.yml`

**Changes**:

- Add success cases for duplicate drivers, shared connections, all four drivers, placeholders, and thinking levels.
- Add regression cases proving valid `bifrost.session.*` and `bifrost.skills.*` siblings bind under strict mode, while unknown fields in any unified root section fail.
- Add failures for unknown connection, removed `provider`, missing connection/driver/provider model, invalid header names, null header values, missing driver credentials/endpoints, wrong driver option blocks, incomplete Gemini modes, unsupported driver, and unknown properties.
- Assert errors contain configuration paths and never resolved secrets/header values.

### Success Criteria

#### Automated Verification

- [x] Focused binding/catalog tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostPropertiesTest,YamlSkillCatalogTests test`.
- [x] Compiled configuration metadata describes dynamic connection/model entries and driver hints.
- [x] `rg "AiProvider|models\..*\.provider" bifrost-spring-boot-starter/src` returns no intentional production compatibility surface.

#### Manual Verification

- [x] Generated metadata inspection shows the connection/driver distinction and excludes the removed provider field.
- [x] Representative invalid configurations identify the exact path without exposing substituted secrets.

**Implementation Note**: Pause after automated verification for confirmation of the configuration shape and diagnostics before proceeding.

---

## Phase 2: Build and Reuse One Chat Model per Connection

### Overview

Introduce Bifrost-owned driver factories and an immutable named registry constructed once at startup.

### Changes Required

#### 1. Add internal driver factories

**Files**: Package-private classes colocated under `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/` so client construction is not an external Java API.

**Changes**:

- Add an internal `AiConnectionChatModelFactory` contract keyed by `AiDriver`.
- Implement focused OpenAI, Anthropic, Gemini, and Ollama factories using Spring AI 1.1.6 builders.
- Map only the Phase 1 fields; keep retry behavior framework-owned and do not add unplanned transport knobs.
- Convert OpenAI static headers to the Spring AI builder representation without ever logging them.
- Build Gemini API-key or Vertex clients deterministically; load optional credential resources without including their content/path in errors beyond the safe configuration field path.
- Use native `OllamaApi`/`OllamaChatModel`, not the OpenAI compatibility API.

#### 2. Add the named connection registry

**Files**:

- New `NamedAiConnectionRegistry.java` and supporting internal value types.
- `BifrostAutoConfiguration.java`

**Changes**:

- Eagerly build an immutable `Map<String, ChatModel>` once for the default resolver.
- Fail startup with connection-scoped errors when construction fails.
- Guarantee distinct instances for distinct names even when drivers match and identical reuse for aliases sharing one name.
- Register lifecycle cleanup for any constructed resource that exposes a close/destroy contract; do not close auto-configured or externally owned objects because none participate in the new default path.
- Keep the registry, factory contract, concrete factories, and their bean methods package-private and conditional on the absence of an application-provided `SkillChatModelResolver`; leave configuration validation unconditional.

#### 3. Replace auto-configured singleton discovery

**File**: `BifrostAutoConfiguration.java:399`.

**Changes**:

- Remove `ObjectProvider<OpenAiChatModel>`, `AnthropicChatModel`, `GoogleGenAiChatModel`, and `OllamaChatModel` collection into an enum map.
- Wire the default resolver from `NamedAiConnectionRegistry`.
- Preserve only the `@ConditionalOnMissingBean(SkillChatModelResolver.class)` customization boundary.

### Success Criteria

#### Automated Verification

- [x] Registry/factory tests prove two OpenAI and two Ollama names create distinct native model instances.
- [x] Tests prove two aliases sharing one connection receive the same instance and construction occurs once.
- [x] Tests cover Anthropic and both Gemini credential modes with test doubles.
- [x] Custom resolver auto-configuration backs off without constructing default connection clients.
- [x] Focused tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests,NamedAiConnectionRegistryTests,*ConnectionChatModelFactoryTests test`.

#### Manual Verification

- [x] Bean and visibility tests show one registry entry per configured connection and no provider-keyed singleton fallback.
- [x] Startup failures name only the failing connection and safe field path.

**Implementation Note**: Pause after automated verification for confirmation of client construction and lifecycle behavior.

---

## Phase 3: Route Every Skill Through Effective Connection Configuration

### Overview

Make the complete effective configuration the sole routing input while retaining driver-specific request options.

### Changes Required

#### 1. Extend normalized execution identity

**Files**:

- `EffectiveSkillExecutionConfiguration.java`
- `YamlSkillCatalog.java:148`
- Test construction helpers across chat, coordinator, planning, attachment, step, trace, and skill tests.

**Changes**:

```java
public record EffectiveSkillExecutionConfiguration(
        String frameworkModel,
        String connection,
        AiDriver driver,
        String providerModel,
        @Nullable String thinkingLevel) {}
```

- Resolve the connection definition during catalog loading and copy only its nonsecret name and driver into effective configuration.
- Keep thinking-level validation against the model entry.
- Never retain the connection properties object in skill definitions or execution metadata.

#### 2. Destructively replace the resolver API

**Files**:

- `SkillChatModelResolver.java`
- `DefaultSkillChatModelResolver.java`
- `DefaultSkillChatModelResolverTests.java`

**Changes**:

- Replace the provider method with `resolve(String skillName, EffectiveSkillExecutionConfiguration configuration)`.
- Resolve the default model strictly by `configuration.connection()`.
- Include skill, framework model, connection, and driver in missing-entry errors.
- Add no deprecated overload or adapter.

#### 3. Keep request options driver-keyed

**Files**:

- `SkillChatOptionsAdapter.java`
- `SpringAiSkillChatClientFactory.java:74`
- `SpringAiSkillChatClientFactoryTests.java`

**Changes**:

- Rename adapter `provider()` to `driver()` and keep one adapter per driver.
- Pass the complete effective configuration to the resolver.
- Continue setting provider model per request for every driver.
- Preserve OpenAI reasoning effort/GPT-5 sampling behavior, Anthropic/Gemini thinking mapping, and native Ollama options.
- Include safe framework model/connection/driver identity in debug and error output.

#### 4. Verify all execution paths

**Files**: Existing coordinator, mission, planning, step-loop, tool, and attachment tests identified by `rg "new EffectiveSkillExecutionConfiguration"`.

**Changes**:

- Prove direct and planning calls use the selected connection.
- Prove each step reuses the skill's selected client.
- Prove nested YAML skills resolve their own model/connection.
- Prove attachments and tool calls remain on the selected native driver client.
- Prove skill input cannot override connection/base URL/headers.

### Success Criteria

#### Automated Verification

- [x] Resolver/factory tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=DefaultSkillChatModelResolverTests,SpringAiSkillChatClientFactoryTests test`.
- [x] Coordinator, mission, planning, step, attachment, and tool tests pass.
- [x] No production resolver call accepts only a driver enum.
- [x] Model aliases sharing a connection can send different provider model IDs through request options.

#### Manual Verification

- [x] Debug-log tests distinguish two same-driver connections without exposing endpoint or credentials.
- [x] Nested-child resolution tests use the child's configured connection rather than the parent's.

**Implementation Note**: Pause after automated verification for confirmation of direct, planned, nested, tool, and attachment routing.

---

## Phase 4: Unify Descriptors, Traces, Metrics, Errors, and CLI Display

### Overview

Propagate the same safe model identity through every operational surface and remove provider-named trace compatibility fields.

### Changes Required

#### 1. Centralize model identity metadata

**Files**:

- `SkillExecutionDescriptor.java`
- `ModelTraceContext.java`
- New `ModelExecutionIdentity.java` under `com.lokiscale.bifrost.core`.

**Changes**:

- Extend descriptors with nullable connection and `AiDriver`.
- Change trace metadata to exact keys `frameworkModel`, `connection`, `driver`, and `providerModel`, plus existing skill/segment context.
- Provide one helper derived from effective configuration for both frame maps and model request/response records.
- Do not put connection objects, base URLs, credentials, or headers into these types.

#### 2. Update all model-call producers and diagnostics

**Files**:

- `DefaultMissionExecutionEngine.java`
- `DefaultPlanningService.java`
- `StepLoopMissionExecutionEngine.java`
- `SpringAiMissionUserMessageSender.java`
- Relevant trace, planning, mission, step, and attachment tests.

**Changes**:

- Use the centralized identity for frame-open, prepared, sent, response, and failure records.
- Make attachment/provider protocol errors identify framework model, connection, driver, and provider model.
- Assert consistency across direct, planning, nested planning, and step execution.

#### 3. Add bounded connection/driver metric tags

**Files**:

- `UsageMetricsRecorder.java`
- `MicrometerUsageMetricsRecorder.java`
- `SessionUsageService.java` and implementation/call sites.
- Existing usage metrics tests.

**Changes**:

- Pass effective model identity when recording model usage.
- Add normalized `connection` and `driver` tags to model call/unit counters.
- Do not tag by base URL, header, credential, or provider model ID.

#### 4. Update the CLI trace contract

**Files**:

- `bifrost-cli/main.go:848`
- New `bifrost-cli/main_test.go`
- `bifrost-cli/traces/*.ndjson`

**Changes**:

- Render `frameworkModel -> connection (driver/providerModel)` in smart summaries.
- Update development fixtures atomically from `provider` to `driver` and add connection/framework model.
- Add Go tests for smart summary and generic detail rendering.

### Success Criteria

#### Automated Verification

- [x] Trace contract tests assert all four identity fields on frames, prepared/sent/response records, and failures.
- [x] Metrics tests assert bounded connection/driver tags and absence of base URL/provider model tags.
- [x] CLI tests pass: `go test ./...` from `bifrost-cli`.
- [ ] Secret sentinel values do not occur in serialized traces, captured logs, exceptions, meter tags, or descriptor strings. (Focused checks now cover runtime provider-failure traces; captured logs, exception chains, journals, and the complete output matrix remain.)

#### Manual Verification

- [x] CLI output clearly distinguishes native OpenAI and an OpenAI-compatible connection using the same driver.
- [x] Failure output identifies the affected alias/connection without revealing configured transport data.

**Implementation Note**: Pause after automated verification for confirmation of trace and CLI readability.

---

## Phase 5: Add Protocol-Level, Security, and Full-Flow Coverage

### Overview

Exercise the real Spring AI request construction against controlled endpoints without requiring live services.

### Changes Required

#### 1. Add a mock HTTP server test dependency and fixture

**Files**:

- Root `pom.xml`
- `bifrost-spring-boot-starter/pom.xml`
- New test fixture under `src/test/java/.../support`.

**Changes**:

- Add a pinned test-scoped MockWebServer dependency.
- Provide deterministic request capture, queued responses, and secret-sentinel assertions.
- Keep Gemini construction tests on an injected/test-double client if its SDK transport cannot be redirected reliably.

#### 2. Add connection integration scenarios

**Files**: New integration tests under `autoconfigure`, `chat/connection`, and runtime packages.

**Changes**:

- Two OpenAI connections hit different servers and send their own auth/headers.
- One OpenAI and one OpenAI-compatible gateway coexist without a new driver.
- Two native Ollama connections hit different servers and retain Ollama request/options semantics.
- Multiple aliases on one connection send distinct provider model IDs while reusing the client.
- Anthropic and Gemini factories construct their supported credential modes.
- Direct, planning, nested, attachment/vision, structured response, and tool-call paths select the expected connection.
- Protocol errors remain provider errors and do not claim universal OpenAI compatibility.

#### 3. Add redaction and startup-failure coverage

**Changes**:

- Inject unique API-key, custom-header, and credential sentinels and search captured logs, exceptions, traces, metrics, and object strings.
- Verify placeholders resolve for construction but resolved values never appear in diagnostics.
- Verify invalid declarative configuration fails before any skill invocation.

### Success Criteria

#### Automated Verification

- [x] Starter tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`.
- [x] Integration tests use only local mock servers/test doubles and require no provider credentials.
- [x] Tests prove one construction per connection and correct endpoint/model/header routing.
- [ ] Redaction tests cover every Bifrost-owned output surface. (Runtime provider-failure traces are covered; journals, Actuator, and the complete output matrix remain.)

#### Manual Verification

- [x] Captured mock-server requests match Spring AI 1.1.6 protocol expectations for OpenAI, gateway, Anthropic, and Ollama.
- [x] Test failure output remains understandable when two connections use the same driver.

**Implementation Note**: Pause after automated verification for confirmation of integration coverage and redaction evidence.

---

## Phase 6: Convert Samples and Publish Source-Verified Guidance

### Overview

Make the repository demonstrate the new architecture exclusively and document the concepts for both application developers and skill authors.

### Changes Required

#### 1. Convert the sample application

**Files**:

- `bifrost-sample/src/main/resources/application.yml`
- `bifrost-sample/pom.xml`
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleApplicationTests.java`
- `bifrost-sample/README.md`

**Changes**:

- Declare one OpenAI connection reused by multiple aliases and two native Ollama connections.
- Keep OpenRouter as a documented profile/example that does not require a live service in sample tests.
- Remove Spring AI provider auto-configuration starter dependencies that are no longer used by Bifrost-owned connection construction, retaining only dependencies proven necessary by the built sample.
- Assert sample binding/model identity without making live calls.

#### 2. Update root documentation

**File**: `README.md`.

**Changes**:

- Explain model alias, connection, driver, and provider model in that order.
- Show shared OpenAI connection, multiple Ollama servers, and OpenAI-compatible gateway configuration.
- Explain explicit ownership/no `spring.ai.*` merge, property placeholders, static-header sensitivity, native Ollama guidance, compatibility caveats, and troubleshooting.
- Include a concise development conversion example and clearly state that `provider` is removed, not deprecated.

#### 3. Add skill-authoring knowledge

**Files**:

- New `ai/skill-authoring/model-selection-and-connections.md`.
- `ai/skill-authoring/README.md`.

**Changes**:

- Document when an LLM-backed YAML skill must declare `model`, how thinking levels are validated, and why a skill never names a connection/driver/provider model directly.
- Distinguish enforced behavior from recommendations and known OpenAI-compatible limitations.
- Cite stable class/test/fixture anchors following `source-verification.md`.
- Update routing and coverage to “Initial, source-verified.”

### Success Criteria

#### Automated Verification

- [x] Sample tests pass: `.\mvnw.cmd -pl bifrost-sample test`.
- [x] Full reactor passes: `.\mvnw.cmd verify`.
- [x] CLI passes: `go test ./...` from `bifrost-cli`.
- [x] Repository search finds no live sample/fixture using removed `models.*.provider` configuration.
- [x] Skill-authoring claims are supported by the cited tests, fixtures, sample, and production source.
- [x] The README coverage table and routing entry are updated and satisfy the LLM-first standard.

#### Manual Verification

- [x] Automated resolver and protocol tests exercise two model aliases on one OpenAI connection without live credentials.
- [ ] Smoke two native Ollama connections and confirm each reaches its intended endpoint when infrastructure is available.
- [x] Confirm an LLM loading only the new routed skill-authoring topic can choose a model alias and explain why endpoint credentials do not belong in a skill.

**Implementation Note**: Pause after automated verification for final documentation and smoke-test confirmation.

## Testing Strategy

Create a dedicated artifact with `ai/commands/3_testing_plan.md` before implementation. At minimum it should make the removed `provider` property and provider-keyed resolver tests fail first, then build outward through configuration, registry, request routing, operational identity, and full flows.

### Unit Tests

- Strict binding, map keys, cross-references, driver-required fields, field applicability, and safe diagnostics.
- One factory per driver and exact property-to-builder mapping.
- Registry uniqueness/reuse/construction count and custom resolver backoff.
- Full-effective-configuration resolver and driver-specific request options.
- Central trace identity and metric tag generation.
- CLI summary formatting.

### Integration Tests

- Mock OpenAI/native gateway, Anthropic, and Ollama endpoints.
- Gemini SDK client construction through an injected test double where needed.
- Shared connection/different model IDs, same driver/different endpoints, nested skills, planning/steps, tools, attachments, and structured responses.
- Secret sentinel absence across logs, errors, traces, descriptors, metrics, and strings.

### Manual Testing Steps

1. Start two Ollama endpoints (or two distinguishable proxies) and bind separate named connections.
2. Invoke skills mapped to each endpoint and inspect traces/CLI identity.
3. Invoke two OpenAI model aliases sharing one connection and verify distinct request model IDs.
4. Configure an OpenAI-compatible gateway and verify supported tools/media/structured behavior without treating compatibility as guaranteed.
5. Introduce an unknown connection and bad credential placeholder; confirm startup diagnostics are precise and redacted.

## Performance Considerations

- Eager construction is O(number of connections) and intentionally shifts configuration/client failures to startup.
- The immutable registry gives constant-time lookup and prevents per-request HTTP-client/model creation.
- Model aliases sharing a connection reuse transport pools and model clients.
- Connection and driver are bounded, application-configured metric tags. Base URL and provider model ID are excluded from metric tags to avoid cardinality growth and sensitive topology exposure.
- Static headers should be copied once into immutable factory input rather than rebuilt or logged on each call.

## Migration Notes

There is no supported compatibility period. Repository and developer configuration must change atomically:

```yaml
# Removed
bifrost:
  models:
    fast:
      provider: openai
      provider-model: gpt-4o-mini
```

```yaml
# Required
bifrost:
  connections:
    openai-main:
      driver: openai
      api-key: ${OPENAI_API_KEY}
  models:
    fast:
      connection: openai-main
      provider-model: gpt-4o-mini
```

Custom `SkillChatModelResolver` implementations must adopt the full-effective-configuration method. Development trace fixtures/consumers must replace `provider` with `driver` and add `connection`/`frameworkModel`. No runtime adapter, warning period, or dual-read behavior is planned.

## References

- Original ticket: `ai/thoughts/tickets/eng-support-named-ai-provider-connections.md`
- Planning command: `ai/commands/2_create_plan.md`
- Dedicated testing-plan command: `ai/commands/3_testing_plan.md`
- Feature design lens: `ai/thoughts/framework-feature-design-lens.md`
- Skill-authoring routing and standard: `ai/skill-authoring/README.md`
- Source-verification protocol: `ai/skill-authoring/source-verification.md`
- Current properties: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostModelsProperties.java:14`
- Current provider resolver: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillChatModelResolver.java:10`
- Current client/options routing: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:74`
- Current trace contract test: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceContractTest.java:177`
- Current CLI model summary: `bifrost-cli/main.go:848`
