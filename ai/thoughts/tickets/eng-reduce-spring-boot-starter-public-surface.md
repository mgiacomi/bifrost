# Reduce the Bifrost Spring Boot Starter Public Surface

## Summary

Reduce `bifrost-spring-boot-starter` to a deliberate, enforceable application API with no Bifrost-specific SPI or bean-replacement surface. Internal runtime decomposition, implementation DTOs, constructors, and Spring wiring must not remain public or replaceable merely because changing them would otherwise require updating existing code.

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
- Explicitly codify that Bifrost currently supports no framework-specific extension points or bean replacements.
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

The Application API is a closed allowlist. Anything not explicitly listed here is internal by default; current Java visibility, package placement, constructor use, bean exposure, tests, or historical implementation do not create an additional API category.

The supported Application API consists of these types under a deliberate `com.lokiscale.bifrost.api` package:

- `SkillTemplate` — inject and invoke a YAML skill with an object or map input, optionally observing successful completion;
- `SkillExecutionView` — immutable successful-completion view containing the session ID and application-facing diagnostic events;
- `SkillExecutionEvent` — immutable current-version diagnostic entry using standard Java values rather than runtime DTOs or Jackson tree types;
- `SkillMethod` — annotate deterministic Java methods used as mapped YAML targets;
- `SkillException` — general application-facing Bifrost failure;
- `SkillInputValidationException` — invalid caller input with application-facing validation issues;
- `SkillInputValidationIssue` — immutable validation path, code, and safe message without the internal rejected value.

`SkillTemplate` remains an injectable interface for ordinary application use and test doubles, but implementing it is not a supported SPI and auto-configuration must not back off to an application implementation. Do not add public execution-failure subclasses, failure-code enums, quota/timeout/evidence/output-schema exceptions, or failure diagnostic callbacks until usage evidence justifies a more detailed contract.

At the `SkillTemplate` boundary, preserve `SkillInputValidationException` for caller-input failures and Spring Security's standard `AccessDeniedException` for authorization failures. Wrap other internal runtime failures in `SkillException` with a safe application-facing message and retain the original exception only as a diagnostic cause; internal cause types and fields are not supported contracts.

The internal `SkillTemplate` implementation must obtain trusted authentication from the current Spring Security context at invocation time and seed the Bifrost session so root, nested, and asynchronous RBAC checks use the same authoritative identity. Do not add authentication as a caller-supplied `SkillTemplate` argument.

## Public DTO Leakage

Explicitly review and correct transitive surface leakage, including:

- `SkillExecutionView` exposing `ExecutionJournal`;
- `ExecutionJournal` exposing `JournalEntry`;
- `JournalEntry` exposing core journal enums and raw runtime payload representation;
- `SkillInputValidationException` exposing a validation type from an implementation-oriented runtime package;
- any public API or SPI signature that forces an otherwise internal type to remain public.

Use application-facing immutable views where information is intentionally supported. Do not preserve the current type graph solely for source compatibility.

## Supported SPI

There are no supported Bifrost-specific SPIs at this time. This ticket must not promote an existing interface or conditional Spring bean into a supported customization contract merely because the implementation already permits replacement.

The following classifications are resolved requirements for this work:

- `AccessGuard` is internal implementation. It was introduced by ENG-014 to centralize the framework's duplicated RBAC checks across discovery and execution. It accepts internal session and capability models, has no documented application customization scenario, and was not intended as an application-owned authorization-policy SPI.
- `SkillChatModelResolver` is internal implementation. It was introduced by ENG-027 as infrastructure for selecting the internally constructed `ChatModel` associated with a skill's effective configuration. The original implementation plan explicitly sought to keep it internal rather than create a public extension burden.
- The later named-connections plan and implementation promoted `SkillChatModelResolver` to a supported application override through `@ConditionalOnMissingBean` and a custom-resolver backoff test. That promotion was not intended and is superseded by this ticket. Remove the override behavior and the test that codifies it rather than preserving either as a compatibility contract.
- Metrics recorders, advisor resolvers, virtual-file/ref storage components, registries, planners, validators, coordinators, routers, factories, clocks, executors, trace readers/writers, and similar runtime seams are internal implementation, not supported SPIs.

Applications use Bifrost through the supported application API, documented configuration and manifest contracts, and standard ecosystem value types or integrations such as Spring `Resource`, Spring Security authentication/authorities, and Micrometer. Consuming a standard Spring or Micrometer contract does not make Bifrost's adapter or wiring bean a supported Bifrost SPI.

If a future concrete application need justifies a Bifrost-specific extension point, add it through a separate approved ticket that defines its purpose, minimal application-facing types, replacement semantics, documentation, and contract tests.

For any future supported SPI:

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

A comprehensive diagnostic-safety and event-schema redesign is deferred from this ticket. Preserve the current journal projection's selected events and existing redaction behavior while converting the projection into immutable `SkillExecutionEvent` values. Event details may retain the current projected information using immutable standard Java values, but must not expose `JsonNode`, `ExecutionJournal`, `JournalEntry`, trace records, or other runtime representation types, and this ticket must not broaden the view to return the raw trace or additional raw trace payloads. Document that the diagnostic view is current-version, intended for trusted development/debugging contexts, and may contain application business data. Stronger allowlist-based sanitization requires a separate ticket.

## Boundary Mechanism

Research and planning must select the smallest enforceable structure that satisfies the desired surface. Options may include:

- package-private implementation types;
- deliberate `api`, `spi`, and `internal` package boundaries;
- separating API, SPI, core, auto-configuration, and starter artifacts;
- a combination of package and artifact boundaries.

Do not choose a structure solely to minimize the immediate diff. Prefer a boundary that is obvious to LLMs and mechanically enforceable. The plan must resolve this design choice before implementation and must not leave alternatives open.

Use one `bifrost-spring-boot-starter` artifact. Do not split API and auto-configuration into separate Maven artifacts as part of this ticket. Organize the artifact into:

- `com.lokiscale.bifrost.api` for the closed Application API allowlist;
- `com.lokiscale.bifrost.autoconfigure` only for Spring Boot integration types that must remain accessible for auto-configuration, configuration binding, or metadata generation;
- `com.lokiscale.bifrost.internal...` for all other implementation.

Use package-private visibility wherever the selected package structure permits it. An internal type may remain technically public only when cross-package Java access or verified Spring reflection requires it. Every such exception must be mechanically allowlisted with its internal classification and a concrete reason; technical public visibility does not make it Application API. Do not collapse the entire implementation into one Java package merely to eliminate every public modifier.

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

The supported Bifrost-specific SPI and Spring replacement allowlists are both empty. Enforcement must reject `@ConditionalOnMissingBean` on Bifrost infrastructure and must reject application replacement tests for framework-owned beans. Standard conditional integration with ecosystem facilities such as Micrometer does not create a Bifrost SPI; implement optional defaults without advertising Bifrost internals as replaceable application beans.

Prefer an explicit allowlist and dependency rules over a brittle target count. The success condition is zero unclassified exposure, not merely fewer public types.

## Documentation

Update developer-facing documentation to identify:

- the supported application entry points;
- the supported extension points and their intended use cases;
- the fact that other starter implementation details are internal before version 1.0;
- any intentional application API, SPI, configuration, or manifest changes;
- the ephemeral, current-version nature of trace representations where developers encounter them.

Assess `ai/skill-authoring/` impact from actual behavior. Do not update skill-authoring guidance for a purely internal Java boundary change, but update it atomically if manifest, mapping, invocation, trace-debugging, or other author-facing semantics change.

## Planning Artifacts

- Implementation plan: `ai/thoughts/plans/2026-07-15-reduce-spring-boot-starter-public-surface.md`
- Testing plan: `ai/thoughts/plans/2026-07-15-reduce-spring-boot-starter-public-surface-testing.md`

Implementation and code review must use both artifacts. If implementation discovers a required contract change, update this ticket and both plans before accepting behavior that differs from the documented boundary.

## Acceptance Criteria

- [x] Every production top-level type in the starter has been classified.
- [x] Every auto-configured bean has been classified as a supported override or internal infrastructure.
- [x] There are zero unclassified public types.
- [x] There are zero unclassified `@ConditionalOnMissingBean` extension points.
- [x] The supported application API and SPI are documented and mechanically allowlisted.
- [x] The Application API allowlist contains exactly `SkillTemplate`, `SkillExecutionView`, `SkillExecutionEvent`, `SkillMethod`, `SkillException`, `SkillInputValidationException`, and `SkillInputValidationIssue` under `com.lokiscale.bifrost.api`.
- [x] The supported SPI allowlist is explicitly empty, and architecture verification fails if a Bifrost-specific SPI or replaceable Bifrost infrastructure bean is introduced without an approved classification.
- [x] Application API and SPI signatures do not expose internal implementation types.
- [x] `AccessGuard` and `SkillChatModelResolver` are internal, are not application-replaceable Spring beans, and are covered by the public-surface and bean-override enforcement.
- [x] The custom `SkillChatModelResolver` backoff test and any documentation or test language treating it as a supported application override are removed or rewritten to assert internal wiring.
- [x] `SkillExecutionView` and validation errors no longer force implementation-oriented packages to remain public unless their exposed types are deliberately reclassified and documented.
- [x] Internal implementation classes, records, enums, constructors, and interfaces have the narrowest visibility permitted by the selected architecture.
- [x] Internal Spring components are no longer advertised as application replacement points.
- [x] All in-repository callers, tests, samples, and documentation compile and pass after intentional breaking changes.
- [x] No compatibility shims preserve accidentally public Java, Spring, or trace surfaces.
- [x] Current-version trace debugging remains coherent and useful without historical-schema support.
- [x] The diagnostic projection preserves the current selected journal information and existing redaction behavior through immutable standard Java values without exposing raw trace records, Jackson tree types, or internal journal DTOs; broader diagnostic-safety redesign remains out of scope.
- [x] `SkillTemplate` obtains trusted authentication from the Spring Security context internally, preserves `AccessDeniedException`, and does not add a caller-controlled authentication argument.
- [x] Caller-input failures use `SkillInputValidationException`; other non-authorization runtime failures crossing `SkillTemplate` use `SkillException` without adding a larger public exception taxonomy.
- [x] Automated architecture tests prevent new unclassified types, signature leaks, and extension points.
- [x] An LLM-backed integration test uses only the supported application and framework-integration surfaces, standard named-connection configuration, and no replaceable internal beans.
- [x] A real-provider smoke invocation succeeds through the supported `SkillTemplate` facade and application-owned connection configuration.
- [x] The full repository verification suite passes.

## Out of Scope

- Compatibility with external consumers; none exist.
- A version 1.0 compatibility guarantee.
- Historical trace readability, archival analytics, trend reporting, or cross-version trace interchange.
- A comprehensive diagnostic-safety redesign, a new sanitized event vocabulary, or exposing raw traces through the Application API.
- A detailed public execution-failure taxonomy, failure-code model, or failure-observer contract before application usage provides evidence for it.
- Preserving accidentally public types for hypothetical future users.
- Adding extension points without a concrete supported customization scenario.
- Changing configuration or manifest behavior merely as a side effect of reorganizing Java internals.
