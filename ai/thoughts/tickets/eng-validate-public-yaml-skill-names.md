# ENG - Validate Public YAML Skill Names for Provider Tool Portability

**Date:** 2026-07-13

**Status:** Proposed

**Priority:** P1 before expanding the nested HTN sample gallery

**Type:** Pre-release authoring and provider-portability correction

**Depends on:**

- [`eng-separate-public-skills-from-java-targets.md`](eng-separate-public-skills-from-java-targets.md)
- [`eng-simplify-mapped-yaml-skill-manifests.md`](eng-simplify-mapped-yaml-skill-manifests.md)

**Delivery order:** Framework prerequisite 3 of 3. Complete after registry separation and mapped-manifest simplification, then begin new HTN gallery sample implementation only after this ticket is verified.

## Summary

Bifrost uses the YAML manifest `name` as the public skill identity and also publishes that value as the function/tool name presented to model providers for nested skill invocation. `YamlSkillCatalog` currently validates only that the field is nonblank and unique. It accepts spaces, dots, `#`, arbitrary punctuation, leading digits, and unbounded length even though model providers impose narrower function-name constraints.

This is already visible in the repository: production sample skills use portable camelCase names such as `expenseLookup`, while most starter test fixtures use dotted names such as `mapped.method.skill` and `root.visible.skill`. Those dotted names can pass catalog loading and local unit tests but are not portable provider tool names.

Establish one provider-portable public naming contract at YAML catalog load time. Reject invalid names before registration or execution, migrate fixtures and documentation to the supported form, and keep the YAML name unchanged across registry lookup, `SkillTemplate`, `allowed_skills`, planning, evidence, traces, and provider-facing tool definitions.

The project has no production release, so this is a direct validation/migration change. Do not introduce sanitization, hidden provider aliases, or compatibility fallbacks.

## Problem Evidence

### Current validation boundary

`YamlSkillCatalog.loadDefinition` calls `validateRequiredField(resource, "name", manifest.getName())`, then stores the definition under that exact value. It does not validate characters, leading characters, or length (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:71-87`, `YamlSkillCatalog.java:137-147`).

`YamlSkillCapabilityRegistrar` copies the same YAML name into `CapabilityMetadata` and `CapabilityToolDescriptor` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:44-60`, `YamlSkillCapabilityRegistrar.java:86-100`). `DefaultToolCallbackFactory` then uses `capability.tool().name()` as the Spring AI function-tool name (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java:81-89`).

The framework therefore has no translation boundary:

```text
YAML name
  -> catalog key
  -> public registry key
  -> allowed_skills / plans / evidence / traces
  -> provider function-tool name
```

### Provider constraints

- OpenAI function names allow letters, digits, underscores, and dashes with a maximum length of 64: [OpenAI API reference](https://platform.openai.com/docs/api-reference/runs/createThreadAndRun).
- Anthropic documents the regex `^[a-zA-Z0-9_-]{1,64}$`: [Anthropic tool-use documentation](https://docs.anthropic.com/ko/docs/agents-and-tools/tool-use/implement-tool-use).
- Gemini recommends descriptive function names without spaces or special characters and specifically recommends underscores or camelCase: [Gemini function-calling documentation](https://ai.google.dev/gemini-api/docs/function-calling).
- Ollama examples consistently use identifier-like underscore names, but its public documentation does not state a stricter validation regex: [Ollama tool-calling documentation](https://docs.ollama.com/capabilities/tool-calling).

The portable intersection should be enforced by Bifrost rather than allowing authoring to succeed and deferring rejection or unreliable behavior to a selected provider.

## Decision Locks

1. **YAML `name` remains the single public skill identity.** The framework does not introduce a separate provider tool name.
2. **Validate names during YAML catalog loading.** Invalid names fail startup before catalog insertion, capability registration, planning, or provider calls.
3. **Use one provider-portable format:** `^[A-Za-z_][A-Za-z0-9_]{0,63}$`.
4. **The maximum length is 64 characters.** Count Java string characters after YAML parsing; do not silently truncate.
5. **Dots, dashes, spaces, `#`, slashes, colons, and other punctuation are invalid.** Although some providers accept dashes, the contract uses the portable intersection and favors identifier-like names across all supported providers.
6. **Names are case-sensitive, as today.** This ticket does not normalize case or impose lowerCamelCase as the only style.
7. **Do not sanitize or rewrite invalid names.** Startup errors must show the invalid value, required format, and representative valid examples.
8. **Do not add aliases or compatibility fallback.** All references migrate atomically to the corrected YAML name.
9. **Every public-name reference uses the exact validated YAML value.** This includes registry keys, `SkillTemplate`, `allowed_skills`, plan capabilities, evidence `tool_evidence` keys, journals, traces, metrics, samples, and documentation.
10. **Java `mapping.target_id` is not governed by the public-name regex.** It remains internal `beanName#methodName` syntax in the separate implementation-target namespace.
11. **Other author-controlled identifiers are out of scope.** Input/output property names, evidence IDs, task IDs, model catalog keys, bean names, and target IDs require their own analysis before sharing this rule.
12. **Do not add provider-specific name mapping.** If a future provider cannot support the portable contract, address that provider integration explicitly rather than creating hidden aliases.

## Goals

- Fail fast with an actionable authoring error instead of a provider-specific runtime rejection.
- Make every catalog-valid public YAML skill name safe to publish as a provider function/tool name.
- Preserve one identity across authoring, invocation, orchestration, evidence, and traces.
- Remove dotted-name fixtures that currently imply unsupported public syntax.
- Give new HTN gallery samples a stable naming convention before they expand the fixture and documentation surface.

## Non-Goals

- Do not rename Java methods or change `mapping.target_id` syntax.
- Do not create a public-name-to-provider-name translation table.
- Do not automatically convert dots/dashes/spaces to underscores or camelCase.
- Do not add deprecation aliases for old fixture names.
- Do not redesign case sensitivity or add case-insensitive collision detection.
- Do not validate unrelated manifest identifiers with this regex.
- Do not change provider selection, tool schemas, planning, RBAC, evidence semantics, or execution behavior beyond public-name validation and reference migration.
- Do not add an HTTP catalog endpoint or SkillBuilder UI.

## Desired Validation

Add one focused validator or catalog helper with behavior equivalent to:

```text
valid:   expenseLookup
valid:   expense_lookup
valid:   _internalStyleAllowed
valid:   Skill2

invalid: mapped.method.skill
invalid: expense-lookup
invalid: 2expenseLookup
invalid: expense lookup
invalid: expenseService#getLatestExpenses
invalid: <more than 64 characters>
```

Example startup error shape:

```text
Invalid YAML skill '<resource>' for field 'name': invalid public skill name
'mapped.method.skill'. Names must match ^[A-Za-z_][A-Za-z0-9_]{0,63}$
(1-64 characters; start with a letter or underscore; then use only letters,
digits, or underscores). Example: mappedMethodSkill.
```

Exact punctuation may vary, but the field, invalid value, format, and remedy must remain present.

## Implementation Scope

### Catalog validation

- Add the portable-name pattern and length semantics at `YamlSkillCatalog`'s authoring boundary.
- Validate after the existing nonblank check and before other logic stores or publishes the name.
- Preserve exact case and exact validated text; do not trim a name into validity.
- Add focused valid/invalid fixtures for leading characters, punctuation, length boundaries, and Unicode/non-ASCII characters.

### Fixture and test migration

- Rename dotted YAML `name` values under `bifrost-spring-boot-starter/src/test/resources/skills/` to provider-safe identifier names.
- Update every matching reference in `allowed_skills`, evidence contracts, Java assertions, plan/tool fixtures, and expected trace/journal payloads.
- Prefer descriptive lowerCamelCase for migrated repository fixtures while keeping the validator broader than that style.
- Keep tests focused on their original behavior; avoid mixing unrelated assertion rewrites into the name-validation tests.

### Samples and documentation

- Verify all sample manifest names remain valid; current sample names are already camelCase.
- Document the public-name format in the root README and AI skill-authoring knowledge base.
- State clearly that `mapping.target_id` intentionally uses different internal syntax.
- Add the final validator/test anchors to `ai/skill-authoring/mental-model.md` and update the coverage matrix in `ai/skill-authoring/README.md`.

## Test Plan

### Catalog validation

1. Accept one-character letter and underscore names.
2. Accept camelCase, underscore-separated, and names containing digits after the first character.
3. Accept exactly 64 characters.
4. Reject blank names through the existing required-field path.
5. Reject a leading digit.
6. Reject dots, dashes, spaces, `#`, slashes, colons, and Unicode/non-ASCII characters.
7. Reject 65 or more characters.
8. Report the YAML resource, `name` field, invalid value, regex/length rule, and a valid example.

### Identity propagation

1. A validated YAML name is unchanged in `YamlSkillCatalog` and `CapabilityRegistry`.
2. `SkillTemplate` invokes the exact validated name.
3. `allowed_skills` resolves the exact validated child name.
4. Plans, tool callbacks, evidence producer keys, metrics, journals, and public traces use the same name.
5. Mapped YAML names remain independent from `beanName#methodName` target IDs.

### Migration verification

1. No YAML skill manifest in starter tests or samples declares an invalid public name.
2. No Java test/assertion references a retired dotted skill name.
3. Sample context and controller tests continue to pass without sample behavior changes.
4. Full starter and sample suites pass after the fixture migration.

## Acceptance Criteria

- [ ] Catalog loading enforces `^[A-Za-z_][A-Za-z0-9_]{0,63}$` for every YAML skill `name`.
- [ ] Invalid names fail startup with the resource, field, value, rule, and remedy.
- [ ] Names are neither trimmed, sanitized, normalized, truncated, nor aliased.
- [ ] Exactly 64 characters is accepted and 65 characters is rejected.
- [ ] Public name case sensitivity remains unchanged.
- [ ] All repository YAML skill fixtures and their references use valid names.
- [ ] Sample skill names remain behaviorally unchanged.
- [ ] `SkillTemplate`, visibility, planning, evidence, metrics, journals, and traces use the exact validated YAML name.
- [ ] `mapping.target_id` retains `beanName#methodName` syntax and is not subjected to the public-name validator.
- [ ] Root README and AI authoring guidance document the final source-verified rule.
- [ ] No provider-name translation or compatibility layer is introduced.
- [ ] Starter and sample test suites pass.

## Risks and Mitigations

### Large fixture rename diff

Most starter YAML fixtures use dotted names, so the mechanical migration will touch many resources and assertions.

**Mitigation:** Land this after registry separation and mapped-manifest simplification, perform the rename as a dedicated ticket, use repository-wide search verification, and keep behavior changes limited to the new name validator.

### Hidden string references

Skill names appear in manifests, `allowed_skills`, evidence mappings, Java tests, plans, journals, metrics, and trace assertions.

**Mitigation:** Inventory exact dotted names before editing, update each name atomically, and finish with searches for retired names plus full module tests.

### Overly provider-specific validation

A rule tailored to one provider could reject portable names unnecessarily or still admit names another provider cannot use.

**Mitigation:** Enforce the conservative documented intersection—ASCII letter/underscore start, then ASCII letters/digits/underscores, maximum 64—and keep provider-specific translation out of scope.

### Confusing public names with internal target IDs

The new public rule intentionally rejects `#`, while Java mapping IDs require it.

**Mitigation:** Validate only `YamlSkillManifest.name`, document the namespace boundary, and retain focused mapping tests using `beanName#methodName` unchanged.

## Source Anchors

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/tool/DefaultToolCallbackFactory.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/SkillVisibilityResolverTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java`
- `bifrost-spring-boot-starter/src/test/resources/skills/`
- `bifrost-sample/src/main/resources/skills/`

## Related Work

- [Public YAML skill and Java target separation](eng-separate-public-skills-from-java-targets.md)
- [Mapped YAML manifest simplification](eng-simplify-mapped-yaml-skill-manifests.md)
- [Bifrost Framework Feature Design Lens](../framework-feature-design-lens.md)
