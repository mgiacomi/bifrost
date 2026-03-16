# Ticket: eng-005-chatclient-binding.md
## Issue: Implement Skill Model Resolution and Thinking-Level Validation

### Context
Bifrost should favor reproducibility by default. A skill must explicitly declare the model it intends to use, and may optionally declare a `thinking_level` when the selected model supports it. The framework owns the catalog of allowed models in `application.yml`, validates skill configuration at boot, and resolves the effective `ChatClient` settings through a shared resolver/factory rather than fixed `heavy` and `light` bindings.

### Requirements
1. **Framework Model Catalog:**
   - Define `bifrost.models` configuration in `application.yml` as the framework-level source of truth for allowed models.
   - Each configured model entry must include the exact provider model name used at runtime.
   - Each configured model entry may declare the supported `thinking_levels` for that model. Models without thinking support may omit this list.
   - Utilize Spring Boot `@ConfigurationProperties` to bind this catalog into strongly typed framework configuration.
2. **Skill Execution Configuration:**
   - LLM-backed YAML skills must declare `model` and may optionally declare `thinking_level`.
   - A skill's `model` value must exactly match one configured framework model entry.
   - If a skill omits `thinking_level` and the selected model supports thinking, Bifrost must default the effective thinking level to `medium`.
   - If a skill omits `thinking_level` and the selected model does not support thinking, Bifrost must apply no thinking-level setting.
3. **Validation:**
   - Validate all YAML skill model settings during boot.
   - Fail Spring application startup when a skill references a model not present in the framework catalog.
   - Fail Spring application startup when a skill specifies a `thinking_level` not supported by its selected model.
4. **Resolver / Factory Design:**
   - Define a resolver/factory component that accepts the skill's effective execution configuration and returns a configured Spring AI `ChatClient`.
   - Use direct provider-native Spring AI option types for OpenAI, Anthropic, Gemini, and Ollama rather than a starter-local synthetic options wrapper.
   - Avoid predefining fixed `heavy` and `light` client beans.
   - Store the skill's effective model selection in capability metadata so execution remains deterministic and traceable.
5. **MVP Boundaries:**
   - Scope this ticket to YAML skill model resolution and validation.
   - Do not expand this ticket to redefine `@SkillMethod` semantics; that should be handled in a follow-up ticket.

### Acceptance Criteria
- Integration test binds the framework model catalog from `application-test.yml`.
- A valid YAML skill with an explicit `model` and omitted `thinking_level` resolves to `medium` when the selected model supports thinking.
- A valid YAML skill targeting a model without thinking support resolves without applying a thinking level.
- Boot fails when a YAML skill references an unknown model.
- Boot fails when a YAML skill specifies an unsupported `thinking_level`.
- Resolver/factory logic produces a correctly configured `ChatClient` for a skill based on the validated effective execution configuration.
