# Define the Pre-1.0 Compatibility and Contract Policy

## Summary

Bifrost is an unpublished, pre-1.0 framework used only by this repository. Update the framework design lens and development-process commands so LLM-driven work favors a small, coherent design over preserving accidental exposure or obsolete behavior.

The process must distinguish supported contracts from technical visibility. It must not infer a compatibility obligation merely because a type, constructor, interface, Spring bean, test, trace record, or previous behavior exists.

This ticket must be completed before `eng-reduce-spring-boot-starter-public-surface.md` is planned or implemented.

## Context

Current planning guidance tells agents to maintain backwards compatibility during refactoring without defining which surfaces are protected. Current review guidance asks broadly for source, binary, serialization, trace, and extension-point compatibility. In a codebase where most starter types are public and most infrastructure beans are replaceable, those instructions encourage overloads, aliases, fallbacks, adapters, deprecated paths, and dual behavior.

There are no external consumers or out-of-repository compatibility commitments. None are expected until Bifrost is published as version 1.0.

## Desired Policy

Until Bifrost adopts a version 1.0 compatibility policy:

- Coherent design and a small durable surface take priority over preserving accidental exposure.
- Breaking changes are acceptable when they remove baggage, clarify contracts, reduce accidental public surface, or improve the framework's design.
- All in-repository callers, tests, samples, fixtures, and documentation should be updated atomically with an intentional breaking change.
- A public modifier, interface, constructor, record, Spring bean, `@ConditionalOnMissingBean`, existing test, fixture, or previous implementation does not by itself establish a supported contract.
- Compatibility machinery must not be added without identifying a protected contract and explaining why an atomic breaking change is inappropriate.
- Tests establish existing behavior but do not independently establish that the behavior is a supported external contract.

## Contract Classification

Framework work must classify affected surfaces before deciding whether to preserve them:

1. **Application API** — deliberately supported entry points used by ordinary application developers.
2. **Supported SPI** — deliberately supported customization or replacement points.
3. **Configuration and manifest contracts** — documented `bifrost.*` properties, YAML skill syntax, validation, defaults, and author-facing semantics.
4. **Persisted or serialized contracts** — formats deliberately intended for durable or cross-version use.
5. **Ephemeral diagnostic formats** — traces and related representations intended to debug and understand executions from the current implementation.
6. **Internal or accidentally exposed implementation** — runtime decomposition, wiring seams, implementation DTOs, constructors, beans, and behaviors that have not been deliberately classified above.

Documentation, an explicit API/SPI allowlist, an approved ticket, and verified consumer usage are evidence of a supported contract. Technical visibility alone is evidence of exposure, not evidence of a compatibility promise.

## Configuration and Manifest Posture

Documented configuration and manifest behavior is deliberate and must always be assessed. It may still change before 1.0, but an intentional change must:

- be identified explicitly in the ticket and plan;
- explain the resulting developer or skill-author impact;
- update all in-repository configuration, manifests, fixtures, samples, tests, and guidance atomically;
- prefer one coherent new contract over aliases, legacy parsing, precedence rules, or dual behavior;
- add a migration mechanism only when the ticket explicitly requires one.

## Trace Posture

Execution traces are a real-time debugging and execution-understanding tool. They are not a historical analytics, audit-history, trend-analysis, archival, or cross-version interchange format.

The process must therefore treat trace usefulness as a protected product goal while treating trace representation and storage compatibility as non-goals:

- Preserve current-run diagnostic usefulness, accuracy, ordering, failure visibility, security boundaries, and sensitive-data redaction.
- Keep the current writer, reader, projector, and debugging tools coherent with each other.
- Freely change trace schemas, record types, field names, storage layout, and projection behavior when doing so makes the current tool clearer or more useful.
- Do not add legacy readers, schema migrations, version adapters, dual record formats, or historical compatibility fixtures unless a future ticket explicitly changes this policy.
- Update or remove obsolete trace fixtures and compatibility tests as part of the same change.
- Historical trace readability across revisions is explicitly not required.

## Required Documentation Changes

### Framework design lens

Update `ai/thoughts/framework-feature-design-lens.md` to:

- add a clearly labeled current pre-1.0 compatibility posture after the living-document section;
- define the contract classifications and trace posture above;
- state that accidental exposure does not become a contract merely by existing;
- add review questions covering contract classification, evidence, public-surface change, proposed shims, protected consumers, and removal conditions;
- require feature analysis to record the contract classification, intended breaking changes, and shim/no-shim decision.

The lifecycle posture should be easy to replace with a version 1.0 policy later without rewriting the document's enduring design principles.

### Research command

Update `ai/commands/1_research_codebase.md` so API and compatibility research:

- separates technical exposure from documented contract status;
- inventories public declarations, beans, constructors, tests, documentation, configuration, serialization, and in-repository usage as separate evidence;
- does not label a surface a supported API solely because it is public or replaceable;
- remains descriptive rather than recommending changes.

### Planning command

Update `ai/commands/2_create_plan.md` to:

- require reading the framework design lens for framework work;
- replace the blanket `Maintain backwards compatibility` refactoring instruction;
- require classification before compatibility decisions;
- require a `Contract and Compatibility Impact` section in framework plans;
- require that section to identify affected API, SPI, configuration/manifest, durable serialization, ephemeral trace, and internal surfaces;
- record evidence, intended breaks, in-repository consumers to update, public-surface delta, and an explicit shim/no-shim decision;
- prohibit final plans containing an unexplained compatibility mechanism or unresolved contract classification.

### Testing-plan command

Update `ai/commands/3_testing_plan.md` to:

- state that tests do not independently create compatibility promises;
- preserve compatibility tests only for surfaces classified as protected;
- update or remove tests that encode intentionally obsolete internal behavior;
- avoid requiring old and new behavior simultaneously when a break is approved;
- include boundary tests when work changes application API, SPI, or accidental exposure;
- validate current trace coherence and usefulness without requiring historical trace readability.

### Implementation command

Update `ai/commands/4_implement_plan.md` to:

- require following the plan's contract classification;
- prohibit unplanned overloads, aliases, fallbacks, adapters, deprecated paths, legacy readers, duplicate interfaces, and dual behavior;
- require complete removal of intentionally obsolete paths and atomic updates to repository consumers;
- treat discovery of an unclassified documented contract as a plan mismatch instead of silently adding a shim;
- require a final check for accidental public types, leaked internal signature types, and unintended Spring extension points.

### Code-review command

Update `ai/commands/5_code_review.md` to:

- classify the surface before evaluating compatibility;
- state that technical visibility and existing tests are not proof of a compatibility promise;
- avoid reporting approved pre-1.0 internal breaks as compatibility defects;
- treat unjustified shims and preservation of obsolete behavior as maintainability findings;
- check whether new public types and `@ConditionalOnMissingBean` beans were deliberately classified;
- check that approved obsolete behavior was removed rather than retained behind a fallback;
- limit compatibility-path test expectations to contracts classified as protected;
- apply the explicit ephemeral trace policy during review.

## LLM Instruction Requirements

The resulting instructions must be direct and operational:

- State both the positive rule (what is protected) and negative rule (what does not establish protection).
- Define the default action for internal and accidentally exposed surfaces.
- Name common shim forms explicitly so agents do not reinterpret them as harmless convenience.
- Require evidence and a decision rather than asking agents merely to "consider compatibility."
- Keep the canonical policy in the design lens and repeat only short, action-specific rules in commands to reduce drift.
- Use the same contract-category terminology consistently across research, planning, testing, implementation, and review.

The following sentence, or a semantically identical statement, should appear where agents make compatibility decisions:

> A public modifier, interface, constructor, Spring bean, `@ConditionalOnMissingBean`, existing test, or previous implementation does not by itself establish a supported contract.

## Acceptance Criteria

- [ ] The framework design lens contains a clear current pre-1.0 compatibility posture.
- [ ] The design lens distinguishes application API, supported SPI, configuration/manifest contracts, durable serialization, ephemeral diagnostics, and internal implementation.
- [ ] The trace policy explicitly rejects historical and cross-version readability as a requirement while preserving current diagnostic usefulness and security.
- [ ] All five development commands use the same contract-classification vocabulary and decision model.
- [ ] The blanket refactoring instruction to maintain backwards compatibility is removed.
- [ ] Framework plans must include an evidence-backed contract and compatibility assessment.
- [ ] Testing guidance no longer treats existing tests as automatic compatibility contracts.
- [ ] Implementation guidance explicitly prohibits unplanned compatibility mechanisms.
- [ ] Review guidance treats unjustified shims and accidental surface growth as actionable maintainability risks.
- [ ] The commands distinguish approved breaking changes from compatibility regressions.
- [ ] The process supports intentional breaking changes to documented configuration or manifests only when their impact and atomic repository updates are explicit.
- [ ] No source-code behavior is changed by this policy ticket.

## Out of Scope

- Declaring the final version 1.0 compatibility policy.
- Reducing the starter's Java or Spring surface; that is handled by `eng-reduce-spring-boot-starter-public-surface.md`.
- Preserving compatibility for hypothetical future consumers.
- Adding trace schema versions, legacy readers, migrations, or archival support.
- Reorganizing Maven modules or Java packages.

