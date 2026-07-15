---
audience: bifrost-skill-builder
status: development
applies_to: current-repository-checkout
coverage: initial-source-verified
---

# Model Selection and Named AI Connections

## Applicability

Use this document when an LLM-backed YAML skill needs a model, when an application must route models to endpoints/accounts, or when diagnosing model resolution. Mapped YAML skills route directly to Java targets and MUST NOT declare model execution fields.

## Authoring model

The author-facing chain is:

`skill model` → `bifrost.models` framework alias → `bifrost.connections` connection → built-in driver → provider model ID

- An LLM-backed skill MUST set `model` to a configured framework model alias.
- A model alias MUST set `connection` and `provider-model`.
- The connection MUST exist and MUST choose exactly one built-in driver: `openai`, `anthropic`, `gemini`, or `ollama`.
- Connection names and framework model names are application-owned and case-sensitive map keys.
- Multiple connections MAY use the same driver. Use separate names when endpoints, accounts, credentials, headers, or protocol options differ.
- `default-model` has no special fallback behavior; it is an ordinary alias.

```yaml
bifrost:
  connections:
    local-primary:
      driver: ollama
      base-url: http://localhost:11434
    local-secondary:
      driver: ollama
      base-url: http://localhost:11435
  models:
    summarizer:
      connection: local-primary
      provider-model: qwen3:8b
    planner:
      connection: local-secondary
      provider-model: granite4:latest
```

The YAML skill declares `model: summarizer` or `model: planner`; it does not declare the driver or endpoint.

## Connection rules

| Driver | Required mode | Optional connection settings |
| --- | --- | --- |
| `openai` | `api-key` | `base-url`, static `headers`, `openai.organization-id`, `openai.project-id`, `openai.chat-completions-path` |
| `anthropic` | `api-key` | `base-url`, `anthropic.completions-path`, `anthropic.version`, `anthropic.beta-version` |
| `ollama` | `base-url` | None; this driver uses Ollama's native API |
| `gemini` | Exactly one of API-key mode or Vertex AI mode | API-key mode uses `api-key`; Vertex mode uses `gemini.vertex-ai: true`, `project-id`, `location`, and optional `credentials-uri` |

Static `headers` are restricted to the OpenAI driver. Provider-specific option blocks MUST match their driver. Unknown `bifrost.*` fields are rejected at startup. Bifrost does not read, merge, or inherit `spring.ai.*` configuration.

The OpenAI driver MAY target OpenAI-compatible chat-completions services through `base-url`, headers, and an explicit path. A base URL ending in `/v1` composes with `/chat/completions`; an unversioned base URL uses `/v1/chat/completions`. Compatibility is a service contract, not inferred by Bifrost. The Ollama driver uses the native `/api/chat` protocol.

Credentials SHOULD come from environment placeholders or an external secret source and MUST NOT be committed. Connection diagnostics, traces, and metrics identify framework model, connection, and driver; they do not expose API keys, header values, base URLs, or credential contents.

## Thinking levels

A model alias MAY declare `thinking-levels`. A skill's `thinking_level` MUST be one of those configured values. If the alias has no configured levels, the skill MUST omit `thinking_level`. Thinking options are adapted to the selected driver at request time; the connection remains reusable across model aliases.

## Migration and diagnosis

1. Create a named entry under `bifrost.connections` for each endpoint/account.
2. Move base URLs, credentials, headers, and provider-specific protocol settings from `spring.ai.*` into that connection.
3. Replace each `bifrost.models.<name>.provider` with `connection` and keep `provider-model`.
4. If two aliases use different endpoints or accounts for the same driver, point them at different named connections.

Startup errors include the complete property path for missing, unknown, or driver-inapplicable settings. Runtime resolution errors identify the skill, framework model, connection, driver, and provider model. Start diagnosis at the named connection, then verify its driver-specific requirements and the alias reference.

## Source verification anchors

- Configuration and validation: `BifrostProperties`, `BifrostPropertiesTest`.
- Skill model resolution: `YamlSkillCatalog`, `EffectiveSkillExecutionConfiguration`.
- Connection construction and lookup: `NamedAiConnectionRegistry`, driver-specific factories, `DefaultSkillChatModelResolver`.
- Request options: `SkillChatOptionsAdapter`, `SpringAiSkillChatClientFactoryTests`.
- Wire behavior: `ConnectionProtocolTest`.
- Operational identity: `ModelExecutionIdentity`, `ExecutionTraceContractTest`, `MicrometerUsageMetricsRecorderTest`.

This topic does not fully specify provider SDK behavior or third-party compatibility guarantees. Inspect the pinned Spring AI version and the target service's protocol documentation when those details determine correctness.
