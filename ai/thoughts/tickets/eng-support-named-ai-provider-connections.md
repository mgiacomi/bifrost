# Ticket: Support Named AI Provider Connections and Multiple Provider Instances

**Status:** Design  
**Priority:** P1  
**Module:** `bifrost-spring-boot-starter` (plus sample and documentation updates)  
**Related areas:** Model catalog, Spring AI model construction, skill execution configuration, tracing, configuration validation  
**Motivating use cases:** Multiple OpenAI models, multiple Ollama servers, OpenAI-compatible gateways such as OpenRouter  

---

## Development compatibility posture

Bifrost is still under development and has no deployments or released configuration contract to preserve. Destructive changes are welcome when they produce a clearer first-release architecture. This ticket therefore intentionally removes the legacy `models.*.provider` configuration and provider-keyed resolver API rather than adding a deprecation cycle, compatibility bridge, or implicit migration behavior. Existing repository samples, fixtures, traces, and tests should be updated atomically with the implementation.

## Summary

Extend Bifrost's named model catalog so a model selects a **named connection** rather than only an `AiProvider` enum value. A connection represents one concrete AI service endpoint and its authentication/transport configuration. Its `driver` selects the Spring AI implementation used to communicate with that endpoint.

This separates three concepts that are currently conflated:

1. **Framework model alias** — the stable Bifrost name referenced by skill YAML, such as `fast`, `vision-extraction`, or `orchestrator`.
2. **Connection** — a configured endpoint/account/credential boundary, such as `openai-main`, `ollama-east`, or `openrouter`.
3. **Provider model ID** — the model identifier sent to the endpoint, such as `gpt-4o-mini`, `qwen3-vl`, or `anthropic/claude-sonnet-4`.

The same connection must support many provider model IDs through request-level options. Multiple connections may use the same driver, enabling multiple OpenAI accounts/endpoints and multiple Ollama servers in one application.

OpenRouter and similar services do **not** require first-class provider enum values. They are modeled as named connections using the `openai` driver and a custom base URL.

## Problem statement

The existing `bifrost.models` catalog already permits multiple model aliases and model IDs:

```yaml
bifrost:
  models:
    fast:
      provider: openai
      provider-model: gpt-4o-mini
    vision-extraction:
      provider: openai
      provider-model: gpt-5.5-mini
    orchestrator:
      provider: openai
      provider-model: gpt-5.5
```

This works when every model uses the same auto-configured OpenAI endpoint and credentials. `OpenAiChatOptions` sets `provider-model` per request, so one `OpenAiChatModel` can call several model IDs.

The resolver, however, stores exactly one `ChatModel` per `AiProvider`:

```text
OPENAI    -> one OpenAiChatModel
ANTHROPIC -> one AnthropicChatModel
GEMINI    -> one GoogleGenAiChatModel
OLLAMA    -> one OllamaChatModel
```

As a result, Bifrost cannot declaratively represent:

- two or more Ollama servers in one organization;
- OpenAI and an OpenAI-compatible gateway at the same time;
- two OpenAI accounts, projects, regions, proxies, or base URLs;
- separate internal OpenAI-compatible endpoints for different workloads;
- two differently configured instances of any other supported provider;
- explicit connection identity in traces, metrics, diagnostics, or policy decisions.

The `provider` field currently answers two different questions: “which protocol/options implementation should be used?” and “which concrete service instance should receive the request?” Those must become separate decisions.

## Objectives

- Allow an application to configure any number of named AI connections.
- Allow multiple connections to use the same driver/provider type.
- Allow many Bifrost model aliases to reuse one connection while selecting different provider model IDs.
- Preserve native provider integrations, including native Ollama support.
- Support OpenAI-compatible services through the OpenAI driver without adding a provider enum member for every compatible vendor.
- Make connection selection deterministic, validated at startup, and visible in operational telemetry.
- Preserve existing skill YAML: skills continue referencing only a Bifrost model alias through `model`.
- Replace the existing `provider`-based model configuration with the named-connection model before the first release.
- Keep credentials out of skill manifests, execution journals, logs, exceptions, and model metadata.
- Retain Bifrost's ability to apply driver-specific request options such as OpenAI reasoning effort or Ollama-native options.

## Non-goals

- Building a general-purpose secrets manager.
- Dynamically accepting arbitrary base URLs or credentials from skill inputs.
- Discovering provider models automatically.
- Load balancing, failover, health-aware routing, or cost-based model selection in the first version.
- Treating OpenRouter as a dedicated provider/driver.
- Guaranteeing that every OpenAI-compatible endpoint implements the complete OpenAI API.
- Replacing Spring AI's portable `ChatModel` abstraction.
- Supporting per-request connection selection by untrusted callers.
- Adding embeddings, image generation, speech, or other non-chat model types as part of this ticket. The configuration model should not prevent those extensions later.

## Terminology and ownership

| Term | Example | Responsibility |
| --- | --- | --- |
| Skill model reference | `model: orchestrator` | Stable alias selected by a YAML skill |
| Framework model | `orchestrator` | Bifrost catalog entry containing connection, provider model, and supported thinking levels |
| Connection | `openai-main` | Concrete endpoint, authentication boundary, and driver choice |
| Driver | `openai`, `ollama` | Spring AI client/model implementation and request-options adapter |
| Provider model | `gpt-5.5` | Model identifier sent to the selected connection |
| Compatible gateway | `openrouter`, internal proxy | A connection using an existing driver, normally `openai` |

Rename the internal `AiProvider` type to `AiDriver` as part of this work so configuration, code, diagnostics, and documentation use one precise term before the first release.

## Proposed configuration

### Named connections and models

```yaml
bifrost:
  connections:
    openai-main:
      driver: openai
      api-key: ${OPENAI_API_KEY}

    ollama-east:
      driver: ollama
      base-url: http://ollama-east.internal:11434

    ollama-vision:
      driver: ollama
      base-url: http://ollama-vision.internal:11434

    openrouter:
      driver: openai
      base-url: https://openrouter.ai/api
      api-key: ${OPENROUTER_API_KEY}
      headers:
        HTTP-Referer: ${OPENROUTER_SITE_URL:https://example.invalid}
        X-Title: ${OPENROUTER_APP_NAME:bifrost}

  models:
    fast:
      connection: openai-main
      provider-model: gpt-4o-mini

    vision-extraction:
      connection: openai-main
      provider-model: gpt-5.5-mini
      thinking-levels: [low, medium]

    orchestrator:
      connection: openai-main
      provider-model: gpt-5.5
      thinking-levels: [low, medium, high]

    local-general:
      connection: ollama-east
      provider-model: qwen3:32b

    local-vision:
      connection: ollama-vision
      provider-model: qwen3-vl

    routed-sonnet:
      connection: openrouter
      provider-model: anthropic/claude-sonnet-4
```

Skills remain connection-agnostic:

```yaml
name: extractInvoice
description: Extracts structured invoice information from an image.
model: vision-extraction
```

### Connection properties

Common v1 properties:

| Property | Required | Meaning |
| --- | --- | --- |
| `driver` | Yes | Selects the Spring AI implementation/options adapter |
| `base-url` | Driver-dependent | Overrides the driver's default endpoint |
| `api-key` | Driver-dependent | Credential, normally supplied through an environment/property placeholder |
| `headers` | No | Static outbound headers for gateways; values are sensitive and must never be logged |

Provider-specific connection blocks should be typed rather than accepting an unrestricted map. Candidate v1 fields include:

- OpenAI: organization ID, project ID, chat completions path if required by the Spring AI version in use.
- Anthropic: base URL and API key.
- Gemini: base URL/project/location/credential fields supported by the selected Spring AI client.
- Ollama: base URL only; authentication headers may still be needed for an organizational reverse proxy.

Exact property names must follow Spring Boot kebab-case conventions. Only expose fields that can be constructed and tested reliably with Spring AI 1.1.x. Do not copy every Spring AI option into Bifrost preemptively.

### Model properties

The model catalog retains:

- `provider-model`
- `thinking-levels`

It adds:

- `connection`

The existing `provider` field is removed. Every model entry must declare exactly one nonblank `connection`.

## Required behavior

### Model and connection resolution

At catalog load/startup:

1. Resolve the skill's `model` to a Bifrost model catalog entry.
2. Resolve the model entry's `connection` to a named connection definition.
3. Resolve the connection's `driver` to the matching Spring AI model factory and Bifrost request-options adapter.
4. Construct or obtain one reusable, thread-safe `ChatModel` per named connection.
5. Apply `provider-model` and model-level request options for each skill request.

Resolution must be by **connection name**, not by driver enum. Two connection names using the same driver must produce distinct `ChatModel` instances.

The resolved execution configuration must retain at least:

- framework model alias;
- connection name;
- driver;
- provider model ID;
- effective thinking level.

The `SkillChatModelResolver` API must accept the complete effective configuration. It must not continue resolving solely from `AiDriver` or expose a deprecated provider-keyed bridge.

Recommended direction:

```java
ChatModel resolve(String skillName, EffectiveSkillExecutionConfiguration configuration);
```

This avoids repeatedly widening the resolver signature when routing metadata grows.

### Lifecycle and reuse

- Build connection-backed `ChatModel` instances once during application startup or lazily with safe memoization.
- Reuse the same model client for all model aliases pointing to the same connection.
- Do not construct a new HTTP client or `ChatModel` per skill invocation.
- Fail application startup for invalid declarative configuration rather than failing on the first model request.
- Preserve Spring lifecycle/cleanup behavior for any constructed resources.

### Request options

- Continue selecting `provider-model` at request time.
- Continue applying driver-specific options through `SkillChatOptionsAdapter`.
- Select the options adapter by connection driver, not connection name.
- A connection controls endpoint/authentication/transport; a model entry controls model identity and declared model capabilities.
- Skill-level `thinking_level` remains validated against the selected model entry.

### Native Ollama support

Retain `OLLAMA` as a native driver even though Ollama exposes OpenAI-compatible endpoints.

Reasons:

- Ollama's OpenAI compatibility intentionally covers a subset of the OpenAI API.
- Spring AI's native `OllamaChatModel` exposes Ollama-specific behavior and request options.
- Native controls include model residency/`keep_alive`, context sizing, GPU/runtime parameters, native thinking behavior, and native structured output formatting.
- Native provider semantics make future capability validation and diagnostics clearer.

Using the OpenAI driver against an Ollama `/v1` endpoint may remain possible as an advanced compatibility configuration, but documentation should recommend the native Ollama driver unless a user has a specific interoperability reason.

The first implementation does not have to expose every Ollama option. It must preserve the native driver and provide a clean place to add typed connection- or model-level Ollama options later.

### OpenAI-compatible endpoints

- Do not add `OPENROUTER`, `VLLM`, `LM_STUDIO`, or similar enum members solely because those services implement an OpenAI-compatible API.
- Configure them as connections with `driver: openai` and an appropriate `base-url`, API key, paths, and optional headers.
- Trace both the driver (`OPENAI`) and connection (`openrouter`) so operators can distinguish native OpenAI traffic from gateway traffic.
- Clearly document that compatibility depends on the endpoint's implementation of features Bifrost uses, including tools, vision/media, structured responses, usage metadata, and reasoning fields.
- Surface provider protocol errors without claiming that an endpoint is fully OpenAI-compatible.

## Development configuration conversion

Repository samples, fixtures, and local development configuration must be converted atomically from:

```yaml
bifrost:
  models:
    existing-model:
      provider: openai
      provider-model: gpt-4o-mini
```

to:

```yaml
bifrost:
  connections:
    openai-default:
      driver: openai
      api-key: ${OPENAI_API_KEY}
  models:
    existing-model:
      connection: openai-default
      provider-model: gpt-4o-mini
```

This is a source/configuration break, not a runtime compatibility mode. `provider` must be rejected as an unknown model property after the change.

### Spring AI auto-configuration ownership

- Named connections are Bifrost-owned and constructed only from `bifrost.connections`.
- Named connections do not inherit or merge standard `spring.ai.*` properties in the first implementation.
- Spring AI auto-configured singleton `ChatModel` beans are not an implicit connection source.
- Missing secrets or endpoints must produce messages naming the connection but never echoing secret values.

## Configuration validation and diagnostics

Validate all declarative configuration at startup.

Consolidate the existing `bifrost`, `bifrost.session`, and `bifrost.skills` configuration-property classes into one strict root `BifrostProperties` aggregate containing `session`, `skills`, `connections`, and `models`. This lets the whole `bifrost.*` namespace reject unknown properties without a root-level binder mistaking valid sibling sections for unknown fields. `execution-trace.*` remains a separate configuration namespace.

Required validation:

- Connection names are nonblank and unique.
- Model names are nonblank and unique under normal Spring map binding rules.
- Every model `connection` references an existing connection.
- Every connection has a supported driver.
- Every model has a nonblank `provider-model`.
- The removed `provider` property is rejected as unsupported configuration.
- Driver-required fields are present after property resolution.
- Header names are valid and header values are non-null.
- Unsupported driver-specific fields fail binding or validation rather than being silently ignored, consistent with the project's configuration policy.
- Thinking levels continue to be validated at catalog load.

Error messages must identify the full configuration path, for example:

```text
bifrost.models.local-vision.connection references unknown connection 'ollama-vision'
```

```text
bifrost.connections.openrouter.api-key is required for driver OPENAI
```

Secrets, authorization headers, and resolved credential values must be redacted in `toString`, logs, actuator output owned by Bifrost, exceptions, and traces.

## Tracing, metrics, and observability

All model-call trace metadata must contain:

- `frameworkModel`
- `connection`
- `driver`
- `providerModel`

This identity must be consistent across planning, step execution, direct skill execution, response receipt, and failures.

Operational requirements:

- Never include API keys or configured headers in trace metadata.
- Add connection as a low-cardinality metric tag only if connection names are application-configured and bounded. Do not tag by base URL.
- Update the unreleased trace contract and `bifrost-cli` display logic atomically to use driver and connection identity.
- Update repository trace fixtures rather than retaining compatibility-only metadata aliases.
- Error messages should name the framework model and connection to make multi-endpoint failures diagnosable.

## Security requirements

- Connection selection is configuration-driven; skill input cannot override it.
- Base URLs and headers are application configuration, not skill-manifest fields.
- Credentials should normally be supplied using environment variables or an external Spring property source.
- Never serialize connection configuration into execution journals.
- Avoid logging full connection objects.
- Static custom headers must be treated as potentially sensitive even when their names are not `Authorization`.
- Preserve normal TLS verification. This ticket does not add insecure-TLS switches.
- If proxy support is added, it must be typed and must not leak proxy credentials.

## Architecture changes

Expected implementation areas:

1. **Configuration properties**
   - Consolidate session, skill, connection, and model properties under one strict root `BifrostProperties` aggregate.
   - Add a named connection map under `bifrost.connections`.
   - Add `connection` to `ModelCatalogEntry`.
   - Remove `provider`; require `connection`.

2. **Effective execution configuration**
   - Carry connection name in `EffectiveSkillExecutionConfiguration`.
   - Preserve framework model, driver/provider, provider model, and thinking level.

3. **Connection registry/factory**
   - Introduce a registry keyed by connection name.
   - Introduce driver-specific factories that construct Spring AI `ChatModel` instances.
   - Keep construction logic out of `YamlSkillCatalog`.

4. **Model resolver**
   - Replace `Map<AiProvider, ChatModel>` with connection-based lookup and rename the enum to `AiDriver`.
   - Preserve `@ConditionalOnMissingBean` customization behavior.

5. **Options adapters**
   - Continue one adapter per driver.
   - Do not create one adapter per connection.

6. **Tracing and descriptors**
   - Add connection identity everywhere model identity is captured.

7. **Documentation and sample configuration**
   - Demonstrate multiple model IDs on one OpenAI connection.
   - Demonstrate two Ollama connections.
   - Show OpenRouter only as an OpenAI-compatible example, preferably in documentation rather than as a required live sample.

Suggested conceptual structure:

```text
YAML skill
  -> framework model alias
      -> named connection
          -> driver-specific ChatModel
      -> provider model ID + declared capabilities
  -> driver-specific request options
```

## Testing requirements

### Configuration binding and validation

- Bind multiple named connections using the same driver.
- Bind multiple model aliases to one connection.
- Reject missing connection references.
- Reject the removed `provider` property as unknown configuration.
- Reject a missing or blank `connection`.
- Reject missing driver-required properties.
- Verify property placeholders work without exposing resolved secrets.
- Verify unknown/unsupported driver values fail clearly.

### Resolver and factory tests

- Two OpenAI connections resolve to distinct `OpenAiChatModel` instances.
- Two Ollama connections resolve to distinct `OllamaChatModel` instances.
- Two model aliases sharing a connection reuse the same `ChatModel`.
- Different provider model IDs are applied through request options.
- The correct driver-specific options adapter is selected for every connection.
- A custom `SkillChatModelResolver` still replaces the default.
- Client construction occurs once per connection, not per request.

### Breaking-change tests

- The removed model `provider` property fails binding with an actionable configuration path.
- Provider-keyed resolver calls and auto-configured singleton fallback are absent.
- Repository fixtures and samples use named connections exclusively.
- CLI trace fixtures and readers use the new driver/connection identity consistently.

### Execution tests

- Two skills using different OpenAI model IDs on one connection route correctly.
- Two skills using different Ollama connections route to the intended endpoint.
- One native OpenAI connection and one OpenAI-compatible connection can coexist.
- Planning and nested skill execution retain the correct connection throughout each model call.
- Attachment/vision messages use the selected connection and driver.
- Tool-calling behavior remains intact for native Ollama and OpenAI drivers.

Tests must use mock HTTP servers or test doubles and must not require live OpenAI, OpenRouter, or Ollama services in CI.

### Security and observability tests

- API keys and custom header values never appear in logs, exceptions, traces, or object string representations.
- Trace records contain framework model, connection, driver, and provider model.
- Failure messages identify the connection without exposing credentials.

## Documentation requirements

Update the root README and sample README with:

- the distinction between model alias, connection, driver, and provider model;
- configuration for several OpenAI models on one connection;
- configuration for multiple Ollama servers;
- an OpenAI-compatible gateway example;
- why native Ollama remains supported;
- a concise development configuration conversion example from `provider` to `connection`;
- credential/property-placeholder guidance;
- compatibility caveats for tools, media, structured output, reasoning, and usage reporting;
- troubleshooting examples for unknown connections and endpoint failures.

Add configuration metadata/descriptions so IDE completion explains `connections`, `driver`, and `connection` accurately.

## Acceptance criteria

- [x] An application can declare at least two named connections using the `openai` driver.
- [x] An application can declare at least two named connections using the `ollama` driver.
- [x] Multiple model aliases can share one connection and select different provider model IDs.
- [x] Skills continue to reference only framework model aliases.
- [x] The resolver selects `ChatModel` instances by connection rather than solely by provider enum.
- [x] Each named connection owns/reuses one appropriately configured Spring AI `ChatModel`.
- [x] OpenAI-compatible services work through `driver: openai` without dedicated enum members.
- [x] Native Ollama remains supported through `OllamaChatModel` and `OllamaChatOptions`.
- [x] The removed `provider` model property is rejected; no legacy routing or deprecation bridge remains.
- [x] Invalid references, removed fields, and missing required fields fail at startup with actionable messages.
- [x] Effective execution configuration and traces include connection identity.
- [ ] Credentials and custom headers are redacted from all Bifrost-owned output. (Focused checks now cover runtime provider-failure traces; journals, Actuator, exception chains, and the complete output matrix still require explicit verification.)
- [x] Direct, planning, nested planning, tool-calling, and attachment flows route through the selected connection.
- [ ] Tests cover shared connections, duplicate drivers, destructive configuration/API replacement, redaction, and routing. (Core cases are covered; the full testing-plan matrix remains.)
- [x] README/sample documentation contains working configuration examples and a concise development conversion note.

## Delivery plan

Recommended implementation sequence:

1. Add connection configuration types, binding validation, and configuration metadata.
2. Extend effective execution configuration with connection identity.
3. Add the connection registry and driver-specific `ChatModel` factories.
4. Change `SkillChatModelResolver` and `SpringAiSkillChatClientFactory` to resolve by effective configuration/connection.
5. Propagate connection identity into traces, descriptors, errors, metrics, and CLI presentation.
6. Add unit and mock-server integration tests.
7. Update sample configuration and documentation.
8. Run the full Maven build and manually smoke one multi-model OpenAI configuration and two Ollama endpoints when test infrastructure is available.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Spring Boot auto-configuration assumes one model bean per provider | Construct all named connection models explicitly in a Bifrost-owned registry; do not use auto-configured singleton beans as implicit connections |
| Provider-specific constructors diverge | Use one small typed factory per driver with focused tests |
| Configuration surface grows without bounds | Expose a minimal common core and only proven typed provider-specific fields |
| OpenAI-compatible endpoints differ subtly | Document feature compatibility; test Bifrost-required request shapes; avoid vendor-specific provider enums |
| Credentials leak through property objects or diagnostics | Redaction tests and no connection-object serialization/logging |
| Custom resolver implementations must change | Replace the resolver signature before release and retain only the `@ConditionalOnMissingBean` bean-override boundary |
| Trace fixtures and CLI assumptions become stale | Update the unreleased trace contract, fixtures, tests, and CLI display atomically to use driver and connection identity |
| Users confuse connection and model aliases | Use consistent terminology, validation paths, examples, and IDE metadata |

## Design decisions proposed for review

| # | Topic | Proposed decision |
| --- | --- | --- |
| 1 | Routing key | Resolve concrete `ChatModel` instances by named connection |
| 2 | OpenRouter | Use an OpenAI-compatible connection; no dedicated driver enum |
| 3 | Ollama | Retain the native Ollama driver |
| 4 | Model selection | Keep provider model ID as a per-request model option |
| 5 | Skill contract | Skills continue referencing only Bifrost model aliases |
| 6 | Compatibility | Make a clean pre-release break: remove `provider`, legacy singleton routing, and compatibility bridges |
| 7 | Client lifecycle | One reusable `ChatModel` per named connection |
| 8 | Customization | Preserve custom resolver override through `@ConditionalOnMissingBean` |
| 9 | Secrets | Configuration/property sources only; never trace or log values |
| 10 | Initial scope | Apply named connections consistently to all existing chat drivers, even though OpenAI and Ollama are the motivating cases |

## Resolved design decisions

1. Rename `AiProvider` to `AiDriver` now.
2. Require explicit named-connection properties; do not inherit or merge `spring.ai.*` properties in v1.
3. Limit static custom headers to the OpenAI driver in v1 and reject them for other drivers.
4. Expose only fields that Spring AI 1.1.x construction tests prove: base URL/API key where applicable, OpenAI organization/project/completions path, Anthropic completions/version fields, Gemini API-key or Vertex project/location/credentials mode, and Ollama base URL. Defer timeout, retry, and proxy configuration.
5. Remove legacy `provider` support and deprecation warnings.
6. Replace `SkillChatModelResolver` with the effective-configuration signature without a deprecated compatibility method; retain bean override behavior.
7. Use `driver` plus `connection` in the unreleased trace contract and update fixtures/readers atomically.
8. Keep connection factories internal in v1; `SkillChatModelResolver` remains the supported application override boundary.
9. Consolidate all `bifrost.*` properties under one strict `BifrostProperties` root so unknown-field rejection applies without conflicting with sibling configuration sections.

## Design discussion notes

- 2026-07-14: Initial ticket created from the need to use several OpenAI model IDs for distinct workloads and multiple Ollama servers within one organization.
- Initial direction: separate model alias, named connection, driver, and provider model identity.
- Initial direction: native Ollama remains valuable even though Ollama supports an OpenAI-compatible endpoint.
- Initial direction: OpenRouter is a configured OpenAI-compatible connection, not a first-class provider.
- Owner: TBD
- Reviewers: TBD
