# Reduce the Bifrost Spring Boot Starter Public Surface

## Summary

Reduce `bifrost-spring-boot-starter` to a deliberate, enforceable application API and supported SPI. Internal runtime decomposition, implementation DTOs, constructors, and Spring wiring must not remain public or replaceable merely because changing them would otherwise require updating existing code.

This work is governed by `eng-define-pre-1-compatibility-contract-policy.md` and must not be planned or implemented until that policy ticket is complete.

## Context

The starter currently presents a much larger technical surface than its ordinary application usage requires:

- approximately 173 production top-level Java types;
- approximately 165 public top-level types;
- 30 public interfaces;
- 39 auto-configured beans using `@ConditionalOnMissingBean`;
- production samples that directly rely primarily on `SkillTemplate`, `SkillExecutionView`, and `@SkillMethod`.

This mismatch makes internal implementation look like supported API and SPI. It encourages maintainers and coding models to preserve constructors, interfaces, runtime DTOs, bean replacement behavior, and trace representations through compatibility shims.

There are no external consumers. All callers, samples, tests, fixtures, manifests, configuration, and documentation that matter are in this repository and may be updated atomically.

## Goals

- Make the normal application invocation and deterministic Java implementation surface obvious.
- Deliberately identify the smaller set of extension points Bifrost is prepared to support.
- Internalize runtime implementation types and Spring wiring seams.
- Prevent application APIs and supported SPIs from leaking internal representation types.
- Prevent future accidental public-surface growth through automated enforcement.
- Remove obsolete shapes completely rather than preserving them with compatibility machinery.

## Required Surface Classification

Inventory every production top-level type and every auto-configured bean in `bifrost-spring-boot-starter` and classify it as one of:

1. **Application API**
2. **Supported SPI**
3. **Configuration binding or framework integration type that must remain accessible**
4. **Ephemeral diagnostic type intentionally exposed to the current debugging experience**
5. **Internal implementation**

For every type or bean that remains externally accessible, record why that accessibility is required and what compatibility obligation, if any, Bifrost is deliberately accepting.

Java visibility, current package placement, interface extraction, constructor use in starter tests, `@Bean`, and `@ConditionalOnMissingBean` are inputs to the inventory but are not reasons by themselves to retain a public contract.

## Application API

Preserve the ordinary developer experience unless research finds a concrete reason to improve it:

- invoke a YAML skill through `SkillTemplate`;
- observe the supported result/session diagnostic view;
- implement a deterministic mapped target with `@SkillMethod`;
- receive application-facing validation and execution errors;
- pass documented input forms, including supported attachment values.

The final application API must use application-facing DTOs and exceptions. Its signatures must not reference internal runtime, planning, registry, trace-storage, validation-implementation, or auto-configuration types.

## Public DTO Leakage

Explicitly review and correct transitive surface leakage, including:

- `SkillExecutionView` exposing `ExecutionJournal`;
- `ExecutionJournal` exposing `JournalEntry`;
- `JournalEntry` exposing core journal enums and raw runtime payload representation;
- `SkillInputValidationException` exposing a validation type from an implementation-oriented runtime package;
- any public API or SPI signature that forces an otherwise internal type to remain public.

Use application-facing immutable views where information is intentionally supported. Do not preserve the current type graph solely for source compatibility.

## Supported SPI

Identify extension points Bifrost deliberately wants application developers to replace or implement. Likely candidates requiring evaluation include security policy, metrics recording, model/provider integration, and any intentionally customizable storage boundary.

For each supported SPI:

- document its purpose and supported customization scenario;
- minimize the types needed to implement it;
- keep implementation classes internal;
- ensure its signature does not expose unrelated runtime internals;
- retain `@ConditionalOnMissingBean` only when application replacement is intentional;
- add focused contract tests for supported replacement behavior.

Do not infer that every existing interface is an SPI.

## Spring Auto-configuration Boundary

Review every bean in `BifrostAutoConfiguration` and distinguish supported overrides from internal construction details.

- Remove `@ConditionalOnMissingBean` where replacement is not a supported application feature.
- Avoid advertising internal algorithm components, registries, validators, coordinators, state services, planners, routers, or implementation factories as supported overrides.
- Make bean factory methods and implementation types no more visible than Spring and the chosen package/module structure require.
- Preserve deterministic auto-configuration and testability without turning every dependency seam into public SPI.
- Document the supported override list and enforce it in tests.

`ROLE_INFRASTRUCTURE` is not an API boundary and does not satisfy these requirements by itself.

## Trace and Diagnostic Boundary

Apply the pre-1.0 trace posture from the policy ticket:

- optimize traces for current-run debugging and execution understanding;
- retain current-version writer/reader/projector/tool coherence and sensitive-data safeguards;
- do not preserve historical trace schemas or expose trace storage records as durable application contracts;
- internalize trace persistence representations unless a type is deliberately required by the current debugging interface;
- remove obsolete trace compatibility constructors, readers, adapters, fixtures, or tests rather than supporting multiple formats;
- prefer a narrow application-facing diagnostic view over leaking the trace runtime model through `SkillExecutionView`.

## Boundary Mechanism

Research and planning must select the smallest enforceable structure that satisfies the desired surface. Options may include:

- package-private implementation types;
- deliberate `api`, `spi`, and `internal` package boundaries;
- separating API, SPI, core, auto-configuration, and starter artifacts;
- a combination of package and artifact boundaries.

Do not choose a structure solely to minimize the immediate diff. Prefer a boundary that is obvious to LLMs and mechanically enforceable. The plan must resolve this design choice before implementation and must not leave alternatives open.

## Compatibility and Migration

- Breaking changes to internal and accidentally exposed Java/Spring surfaces are expected and approved in principle.
- Update all in-repository consumers atomically.
- Do not add deprecated forwarding types, compatibility constructors, aliases, bridge interfaces, duplicate bean names, fallback resolution, legacy readers, or dual behavior to preserve the old surface.
- Document intentional changes to application API, supported SPI, configuration, or manifests if the final design requires them.
- Configuration and manifest behavior should remain unchanged unless a specific improvement is identified, justified, and included in the approved plan.

## Automated Enforcement

Add architecture-level verification that fails when the boundary regresses. The exact implementation may use ArchUnit, build/module rules, reflection-based allowlists, or another repository-appropriate mechanism, but it must prove at least:

- every externally accessible starter type belongs to an approved category;
- application API packages do not reference internal packages in public signatures;
- supported SPI packages do not leak unrelated internal types;
- internal implementation types are not accidentally public where the selected architecture can prevent it;
- the auto-configuration override surface matches an explicit allowlist;
- new `@ConditionalOnMissingBean` extension points cannot be added without deliberate classification;
- representative application usage still compiles and runs through the supported facade.

Prefer an explicit allowlist and dependency rules over a brittle target count. The success condition is zero unclassified exposure, not merely fewer public types.

## Documentation

Update developer-facing documentation to identify:

- the supported application entry points;
- the supported extension points and their intended use cases;
- the fact that other starter implementation details are internal before version 1.0;
- any intentional application API, SPI, configuration, or manifest changes;
- the ephemeral, current-version nature of trace representations where developers encounter them.

Assess `ai/skill-authoring/` impact from actual behavior. Do not update skill-authoring guidance for a purely internal Java boundary change, but update it atomically if manifest, mapping, invocation, trace-debugging, or other author-facing semantics change.

## Acceptance Criteria

- [ ] Every production top-level type in the starter has been classified.
- [ ] Every auto-configured bean has been classified as a supported override or internal infrastructure.
- [ ] There are zero unclassified public types.
- [ ] There are zero unclassified `@ConditionalOnMissingBean` extension points.
- [ ] The supported application API and SPI are documented and mechanically allowlisted.
- [ ] Application API and SPI signatures do not expose internal implementation types.
- [ ] `SkillExecutionView` and validation errors no longer force implementation-oriented packages to remain public unless their exposed types are deliberately reclassified and documented.
- [ ] Internal implementation classes, records, enums, constructors, and interfaces have the narrowest visibility permitted by the selected architecture.
- [ ] Internal Spring components are no longer advertised as application replacement points.
- [ ] All in-repository callers, tests, samples, and documentation compile and pass after intentional breaking changes.
- [ ] No compatibility shims preserve accidentally public Java, Spring, or trace surfaces.
- [ ] Current-version trace debugging remains coherent and useful without historical-schema support.
- [ ] Automated architecture tests prevent new unclassified types, signature leaks, and extension points.
- [ ] The full repository verification suite passes.

## Out of Scope

- Compatibility with external consumers; none exist.
- A version 1.0 compatibility guarantee.
- Historical trace readability, archival analytics, trend reporting, or cross-version trace interchange.
- Preserving accidentally public types for hypothetical future users.
- Adding extension points without a concrete supported customization scenario.
- Changing configuration or manifest behavior merely as a side effect of reorganizing Java internals.

