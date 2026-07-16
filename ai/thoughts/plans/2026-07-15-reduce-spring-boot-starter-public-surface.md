# Reduce the Bifrost Spring Boot Starter Public Surface Implementation Plan

## Overview

Reduce `bifrost-spring-boot-starter` to one closed, mechanically enforced Application API while keeping the existing single Maven artifact. Move all other runtime decomposition under explicit internal packages, remove every Bifrost-specific Spring replacement seam, replace leaked runtime DTOs with immutable application-facing values, and update every repository consumer atomically without compatibility shims.

The supported Application API will contain exactly seven top-level types in `com.lokiscale.bifrost.api`: `SkillTemplate`, `SkillExecutionView`, `SkillExecutionEvent`, `SkillMethod`, `SkillException`, `SkillInputValidationException`, and `SkillInputValidationIssue`. There are no supported Bifrost-specific SPIs and no supported Bifrost bean-replacement points.

## Current State Analysis

- The starter contains 173 production top-level Java types, of which 165 are public and 30 are public interfaces. Only eight top-level types are currently package-private. The public declarations span 21 packages, including 56 public types in `core` and 55 across `runtime` and its subpackages.
- All 39 bean factories in `BifrostAutoConfiguration` use `@ConditionalOnMissingBean`, so registries, validators, routers, planners, state services, factories, executors, advisors, and other construction details appear application-replaceable (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:83-493`).
- Repository production samples import only `SkillTemplate`, `SkillExecutionView`, and `SkillMethod`; starter implementation types appear in sample tests because tests currently inspect internal catalogs, registries, properties, and journals directly.
- `SkillExecutionView` directly exposes `ExecutionJournal` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/SkillExecutionView.java:3-7`). That journal exposes `JournalEntry`, whose signature exposes core enums and Jackson `JsonNode` payloads (`core/ExecutionJournal.java:8-27`, `core/JournalEntry.java:10-47`).
- `SkillInputValidationException` directly exposes `runtime.input.SkillInputValidationIssue`, including an arbitrary rejected value (`skillapi/SkillInputValidationException.java:3-20`, `runtime/input/SkillInputValidationIssue.java:3-9`).
- `DefaultSkillTemplate` already creates and finalizes the session, validates inputs, invokes the runtime, and constructs the completion view, making it the correct translation boundary from internal state to the closed Application API (`skillapi/DefaultSkillTemplate.java:57-105`).
- `BifrostSessionRunner` already accepts an optional Spring Security `Authentication` and stores it on a new session, but `DefaultSkillTemplate` currently calls the overload that supplies no authentication (`core/BifrostSessionRunner.java:77-109`, `skillapi/DefaultSkillTemplate.java:79`).
- `AccessGuard` was introduced by ENG-014 to centralize duplicated RBAC enforcement. Its original plan describes an internal runtime refactor, and its signature depends on `BifrostSession` and `CapabilityMetadata`; it is not an application-owned authorization SPI.
- `SkillChatModelResolver` was introduced by ENG-027 as internal model-selection infrastructure. The original plan explicitly sought to avoid a new public extension burden. The later named-connections work promoted it to a supported override and added `customChatModelResolverBacksOffDefaultConnectionConstruction()` (`BifrostAutoConfigurationTests.java:191-205`); the current ticket explicitly supersedes that promotion.
- README documentation already says `rbac_roles` uses the current Spring Security authentication (`README.md:207`), so capturing that authentication in `SkillTemplate` brings the supported entry point into line with documented behavior rather than changing manifest syntax.
- The current `ExecutionJournalProjector` selects a smaller event set from the trace and applies field-name-based redaction (`runtime/trace/ExecutionJournalProjector.java:42-231`). This ticket will preserve that selection and redaction posture; it will not expose raw trace records or undertake the deferred comprehensive diagnostic-safety redesign.
- The trace CLI reads the NDJSON representation independently and does not import Java types (`bifrost-cli/main.go:85-100`, `:1040-1098`). No trace schema change is required for this work.

## Desired End State

An application depending on the starter has one obvious Java surface in `com.lokiscale.bifrost.api`:

```java
import com.lokiscale.bifrost.api.SkillMethod;
import com.lokiscale.bifrost.api.SkillTemplate;

class InvoiceWorkflow {
    private final SkillTemplate skills;

    InvoiceWorkflow(SkillTemplate skills) {
        this.skills = skills;
    }

    String run(Object input) {
        return skills.invoke("invoiceParser", input);
    }

    @SkillMethod(description = "Looks up an invoice")
    Object lookup(String invoiceId) {
        return invoiceId;
    }
}
```

The API package contains exactly these top-level public types:

| Type | Classification | Supported purpose |
| --- | --- | --- |
| `SkillTemplate` | Application API | Inject and invoke YAML skills with object/map inputs and an optional successful-completion observer. |
| `SkillExecutionView` | Application API | Receive the completed session ID and immutable current-version events. |
| `SkillExecutionEvent` | Ephemeral diagnostic type exposed through the Application API | Receive the current journal projection using `Instant`, strings, immutable maps/lists, scalar values, and nullable frame/route strings. |
| `SkillMethod` | Application API | Mark deterministic Java methods as mapped YAML targets. |
| `SkillException` | Application API | General safe failure from the facade. |
| `SkillInputValidationException` | Application API | Distinguish invalid caller input. |
| `SkillInputValidationIssue` | Application API | Inspect immutable validation path, code, and message. |

All production top-level types not in that table are classified by default as either configuration/framework integration or internal implementation. There is no `spi` package. The only separately allowlisted framework-integration types are `BifrostAutoConfiguration`, `BifrostProperties`, `ExecutionTraceProperties`, and `AiDriver` in `com.lokiscale.bifrost.autoconfigure`; they remain accessible only because Spring Boot discovery, binding, metadata generation, and cross-package configuration use require them. They are not Application API and carry no general source-compatibility promise.

All other types live under `com.lokiscale.bifrost.internal...`. They use package-private visibility wherever same-package collaboration permits. Any internal type that remains technically public because another internal subpackage must reference it is listed in the architecture test with category `INTERNAL_IMPLEMENTATION` and a nonblank reason. New technically public internal types fail verification until deliberately recorded.

`BifrostAutoConfiguration` deterministically creates the framework-owned bean graph. No Bifrost bean factory uses `@ConditionalOnMissingBean`, and the supported override allowlist is empty. Optional standard ecosystem integration is still supported without turning Bifrost adapters into SPIs: application `ObjectMapper`, Spring Security context, Spring `Resource`, and optional Micrometer `MeterRegistry` remain standard inputs where currently documented.

`SkillTemplate` obtains the current authentication from `SecurityContextHolder`, seeds it through the existing authenticated `BifrostSessionRunner` overload, preserves `AccessDeniedException`, preserves `SkillInputValidationException`, and wraps all other runtime failures crossing the facade in `SkillException`. The observer remains success-only.

### Key Discoveries

- A separate API/autoconfigure artifact would hide implementation from the normal compile classpath, but would add module and publication complexity while leaving many implementation classes Java-public. The approved one-artifact package and architecture-test boundary is the smallest enforceable structure for the current unpublished repository.
- A public interface or conditional bean is not evidence of an SPI. The approved SPI and Bifrost bean-replacement allowlists are both empty.
- `SkillTemplate` must remain an interface to support ordinary injection and application test doubles. Application implementation is unsupported, and auto-configuration will not back off to a user implementation.
- The current session runner already has the trusted-authentication overload needed by the facade, so no public authentication parameter or new trusted-metadata abstraction is required.
- Preserving current diagnostic event selection while translating it to standard immutable Java values removes DTO leakage without broadening trace exposure or forcing a premature safety taxonomy.

## What We're NOT Doing

- Splitting API, core, auto-configuration, and starter code into separate Maven artifacts.
- Creating a Bifrost SPI, `spi` package, provider-driver plugin API, security-policy override, metrics-recorder override, advisor override, or storage override.
- Allowing application replacement of `SkillTemplate`, `AccessGuard`, `SkillChatModelResolver`, or any other framework-owned bean.
- Collapsing the entire implementation into one Java package solely to make every declaration package-private.
- Adding deprecated forwarding types, old-package aliases, compatibility constructors, bridge interfaces, bean aliases, fallback resolution, or dual old/new behavior.
- Changing YAML manifest syntax, `bifrost.*` configuration behavior, model/connection semantics, attachment input forms, or RBAC role semantics.
- Adding public timeout, quota, provider, output-schema, evidence, planning, or failure-code types.
- Invoking the completion observer on failed executions or attaching diagnostics to public exceptions.
- Broadening the diagnostic view to raw trace records or performing the deferred allowlist-based diagnostic-safety redesign.
- Preserving historical trace schemas or cross-version trace readability.

## Skill-Authoring Documentation Impact

**Impact**: Affected, limited to source anchors

- **Rationale**: Manifest, mapping, invocation, RBAC, attachment, model-selection, planning, and trace semantics do not change. The Java package relocation changes source anchors used by the knowledge base, and the facade begins honoring the already-documented current Spring Security authentication at the supported entry point.
- **Documents to update**: `ai/skill-authoring/mental-model.md` for the `SkillTemplate`, `DefaultSkillTemplate`, and `SkillMethod` source anchors. Update any other routed document only when its existing anchor points to a moved class; do not add a new RBAC or diagnostics topic in this ticket.
- **Supporting evidence**: README security guidance states the current-authentication behavior; `BifrostSessionRunnerTest` covers authenticated sessions; `DefaultSkillTemplateTest` proves facade capture; existing valid/invalid manifests remain unchanged.
- **Coverage table update**: Not required. No topic is added and no documented manifest or execution semantic changes. “Capability visibility and RBAC” and “Traces and debugging” remain at their current coverage levels.
- **LLM-first usability**: Update stable repository-relative source links and terminology only. Keep the existing topic boundaries and incomplete-coverage statements; do not add duplicated public-surface prose to skill-authoring guidance.

## Contract and Compatibility Impact

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | The closed seven-type allowlist above is supported by the ticket, README invocation examples, production sample imports, and verified repository usage. | Preserve ordinary invocation, mapping, observer, attachment-value, validation, and authorization behavior while intentionally moving packages and replacing leaked DTOs. Update all repository consumers atomically. |
| Supported SPI | None. `AccessGuard`, `SkillChatModelResolver`, metrics, advisors, VFS, registries, planners, validators, coordinators, routers, and factories are internal. | Intentional removal of all Bifrost replacement behavior and tests. Empty SPI and override allowlists are enforced. |
| Configuration and manifest contracts | Documented `bifrost.*`, `execution-trace.*`, YAML syntax, defaults, mappings, model connections, attachments, and `rbac_roles` remain unchanged. Binding types are framework integration rather than Application API. | Preserve executable behavior and fixtures. Update Java imports/source anchors only. |
| Persisted or serialized contracts | No deliberately durable Bifrost format is changed. | No migration or compatibility mechanism. |
| Ephemeral diagnostic formats | `SkillExecutionEvent` replaces the leaked journal DTO graph for current-run application diagnostics. Raw trace writer/reader/CLI schema is unchanged. | Preserve current journal-selected information and existing redaction, translate to immutable standard Java values, and update current-version consumers atomically. No historical reader or adapter. |
| Internal or accidentally exposed implementation | Every non-allowlisted production type and every Bifrost bean construction seam is internal. | Move to `internal` packages, narrow declarations and constructors, remove conditional replacement, and update tests rather than preserve old shapes. |

- **Evidence of supported contracts**: Updated ticket requirements; README invocation, RBAC, input, attachment, and observer documentation; production sample usage; `DefaultSkillTemplateTest`; `SupportedSurfaceIntegrationTest`; `SkillMethodTest`; configuration/manifest fixtures and focused tests.
- **Intended breaks**: Old `skillapi`, `annotation`, `core`, `runtime`, `chat`, `security`, `skill`, `vfs`, `linter`, and `outputschema` Java imports are removed from application consumers; `SkillExecutionView.executionJournal()` is replaced by `events()`; validation issues no longer expose `rejectedValue`; application replacement of any Bifrost bean is removed; internal constructors and interfaces narrow or move.
- **In-repository consumers to update**: starter production and tests; all `bifrost-sample` production controllers, deterministic services, catalog tests, controller tests, and context tests; root README; sample README if it names Java types; `ai/skill-authoring` source anchors; future trace notes containing obsolete durable/versioned language.
- **Public-surface delta**: Remove 158 currently public top-level types from the Application API classification; introduce `SkillExecutionEvent` and `SkillInputValidationIssue` as application-facing replacements; move the seven approved types to `com.lokiscale.bifrost.api`; retain four separately classified Spring integration types; add no SPI or Spring replacement point.
- **Shim decision**: **No shim.** There are no external consumers, and the ticket explicitly approves atomic repository changes. Do not retain old packages, constructors, interfaces, journal DTOs, exception types, bean backoff behavior, or trace compatibility paths.

## Implementation Approach

Build the new boundary from the outside inward. First establish the seven API types and translate the facade to them. Then move and narrow implementation packages, which makes compiler failures enumerate every internal dependency that must be repaired. Next simplify auto-configuration into deterministic framework-owned wiring. Finally add architecture enforcement, migrate all consumers and documentation, and run current-version trace plus full repository verification.

The implementation should use `git mv` for source/test relocations where practical so history remains readable. Package declarations, imports, and test packages may be updated mechanically, but visibility changes and API translation require deliberate edits. Do not mix old and new packages during an intermediate committed state; each implementation phase must compile before proceeding.

## Phase 1: Establish the Closed Application API and Facade Translation

### Overview

Create the seven approved API types, replace leaked validation and journal DTOs, capture trusted authentication, and enforce the minimal exception boundary before reorganizing the rest of the runtime.

### Changes Required

#### 1. Move the approved entry points into `com.lokiscale.bifrost.api`

**Files**:

- Move `skillapi/SkillTemplate.java` to `api/SkillTemplate.java`.
- Move `skillapi/SkillExecutionView.java` to `api/SkillExecutionView.java` and replace its component shape.
- Move `skillapi/SkillException.java` to `api/SkillException.java`.
- Move `skillapi/SkillInputValidationException.java` to `api/SkillInputValidationException.java` and replace its issue type.
- Move `annotation/SkillMethod.java` to `api/SkillMethod.java`.
- Add `api/SkillExecutionEvent.java`.
- Add `api/SkillInputValidationIssue.java`.

**Changes**:

- Keep the four existing `SkillTemplate.invoke(...)` overloads with their current `String`, `Object`, `Map<String,Object>`, and `Consumer<SkillExecutionView>` shapes.
- Define `SkillExecutionView` as an immutable record with `String sessionId` and `List<SkillExecutionEvent> events`; validate the session ID and defensively copy the list.
- Define `SkillExecutionEvent` as an immutable record with `Instant timestamp`, `String level`, `String type`, `Map<String,Object> details`, nullable `String frameId`, and nullable `String route`. Deep-copy maps/lists into unmodifiable standard Java values at construction.
- Define `SkillInputValidationIssue` with only `String path`, `String code`, and `String message`. Do not include `rejectedValue`.
- Keep `SkillException` public constructors for message and message/cause only.
- Keep `SkillInputValidationException` as a `SkillException` subtype with a defensively copied issue list.
- Do not add public constructors or factories whose signatures mention internal or autoconfiguration types.

#### 2. Translate internal validation and diagnostics at the facade

**Files**:

- Move `skillapi/DefaultSkillTemplate.java` to `internal/skillapi/DefaultSkillTemplate.java`.
- Update `runtime/trace/ExecutionJournalProjector.java` and/or add `internal/skillapi/SkillExecutionViewMapper.java`.

**Changes**:

- Keep internal input validation DTOs available to the validation engine, but map each issue to the new public three-field issue before throwing `SkillInputValidationException`.
- Preserve the current journal projector's event selection and redaction logic.
- Map `JournalLevel.name()` and `JournalEntryType.name()` to public strings.
- Convert each Jackson payload into an immutable `Map<String,Object>` of standard Java values. Wrap scalar/text payloads under a stable `message` or `value` key so no `JsonNode` crosses the boundary.
- Copy timestamp, frame ID, and route without exposing `ExecutionJournal`, `JournalEntry`, trace records, or core enums.
- Call the observer only after successful execution, as today.

#### 3. Capture authentication and normalize exceptions at `SkillTemplate`

**Files**:

- `internal/skillapi/DefaultSkillTemplate.java`
- `internal/core/BifrostSessionRunner.java`
- `src/test/java/.../internal/skillapi/DefaultSkillTemplateTest.java`

**Changes**:

- Read `SecurityContextHolder.getContext().getAuthentication()` immediately before opening the session.
- Call the existing `BifrostSessionRunner.callWithNewSession(authentication, ...)` overload; do not add any public authentication argument.
- Preserve `SkillInputValidationException` and `AccessDeniedException` unchanged.
- Preserve an existing `SkillException` unchanged.
- Catch other `RuntimeException` values crossing the facade and throw `new SkillException("Skill '<name>' execution failed.", cause)` without copying the internal exception message into the application-facing message.
- Do not catch `Error` at the facade.
- Add focused tests proving matching authentication is stored on the session and authorizes a protected invocation, missing/mismatched authentication produces `AccessDeniedException`, input failures remain structured, internal runtime failures become `SkillException`, and the observer is not called after failure.

### Success Criteria

#### Automated Verification

- [x] API and facade tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=DefaultSkillTemplateTest,ApplicationApiValueTest,SkillMethodTest test`.
- [x] Diagnostic projection tests pass with no `JsonNode`, journal, or trace type in the public values: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=ExecutionJournalProjectorTest,ExecutionJournalProjectionContractTest test`.
- [x] Security tests prove current-authentication capture: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=DefaultSkillTemplateTest,DefaultAccessGuardTest,BifrostSessionRunnerTest test`.

#### Manual Verification

- [x] The seven public types can be understood without reading internal packages.
- [x] No public signature references `core`, `runtime`, `chat`, `skill`, `autoconfigure`, Jackson, or another implementation package.
- [x] Diagnostics preserve current selected information but do not broaden to raw traces.

---

## Phase 2: Move Runtime Decomposition Behind the Internal Package Boundary

### Overview

Repackage every non-API, non-Spring-integration type under `com.lokiscale.bifrost.internal...`, then narrow declarations, constructors, and methods to the least visibility permitted by actual cross-package usage.

### Changes Required

#### 1. Preserve only the approved Spring integration package

**Files**:

- `autoconfigure/BifrostAutoConfiguration.java`
- `autoconfigure/BifrostProperties.java`
- `autoconfigure/ExecutionTraceProperties.java`
- `autoconfigure/AiDriver.java`
- `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `src/main/resources/META-INF/additional-spring-configuration-metadata.json`

**Changes**:

- Keep these four top-level types in `com.lokiscale.bifrost.autoconfigure` and classify them as configuration/framework integration, not Application API.
- Preserve property prefixes, field names, defaults, validation, metadata names, and auto-configuration discovery.
- Do not expose their types through the seven API signatures.

#### 2. Move all other production packages under `internal`

**Files**: every remaining production Java source currently under `chat`, `core`, `linter`, `outputschema`, `runtime`, `security`, `skill`, `skillapi`, and `vfs`, plus package-private provider connection factories currently in `autoconfigure` that are not one of the four integration types.

**Changes**:

- Preserve subsystem grouping as `internal.chat`, `internal.core`, `internal.linter`, `internal.outputschema`, `internal.runtime...`, `internal.security`, `internal.skill`, `internal.skillapi`, `internal.vfs`, and `internal.autoconfigure` rather than flattening all implementation into one package.
- Move provider connection factories, registries, and safe configuration exceptions to `internal.autoconfigure`; keep only the four approved integration types in the public autoconfiguration package.
- Update package declarations and production/test imports atomically.
- Make top-level types package-private when all production consumers share their package.
- For cross-internal-package collaboration that requires a public modifier, keep the type under `.internal`, narrow public members to those required by the collaborating packages, and add it to the technically-public internal classification map with a concrete reason.
- Narrow constructors used only by same-package tests; move tests into the corresponding internal package rather than retaining public constructors for test convenience.
- Delete obsolete public overloads and compatibility constructors rather than moving them unchanged.

#### 3. Update starter tests to test behavior at the correct boundary

**Files**: all starter tests under the moved packages.

**Changes**:

- Move internal unit tests with their implementation package so package-private behavior remains testable.
- Keep public contract tests in `com.lokiscale.bifrost.api` and auto-configuration contract tests in `com.lokiscale.bifrost.autoconfigure`.
- Replace test-only public constructor use with same-package access or focused internal fixtures.
- Do not preserve an interface or constructor because Mockito or an existing test currently consumes it.

### Success Criteria

#### Automated Verification

- [x] Starter compiles after the complete package move: `.\mvnw.cmd -pl bifrost-spring-boot-starter -DskipTests compile`.
- [x] Starter tests compile with no imports from the removed old packages: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`.
- [x] `rg -n "^package com\.lokiscale\.bifrost\.(chat|core|linter|outputschema|runtime|security|skill|skillapi|vfs)" bifrost-spring-boot-starter/src` returns no production or test package declarations.

#### Manual Verification

- [x] Every non-API type has an unambiguous internal or framework-integration package.
- [x] Remaining technically public internal types exist for verified Java/Spring mechanics, not test convenience or historical exposure.

---

## Phase 3: Make Spring Auto-configuration Deterministic and Non-replaceable

### Overview

Remove the 39 accidental replacement seams and wire one framework-owned bean graph while preserving optional standard ecosystem integrations.

### Changes Required

#### 1. Remove Bifrost bean backoff

**File**: `autoconfigure/BifrostAutoConfiguration.java`

**Changes**:

- Remove every `@ConditionalOnMissingBean` annotation from Bifrost bean methods, including type- and name-based conditions.
- Make all bean methods package-private. Spring supports non-public `@Bean` methods in this non-proxied configuration, and focused context tests must verify discovery and construction after the visibility change.
- Construct the internal `AccessGuard`, `SkillChatModelResolver`, advisor resolver, registries, validators, state services, planners, routers, factories, and `SkillTemplate` unconditionally.
- Remove the `SkillChatModelResolver` condition from `NamedAiConnectionRegistry`; default named-connection construction is always framework-owned.
- Remove `@ConditionalOnBean(SkillChatModelResolver.class)` and similar conditions whose prerequisite is now guaranteed by the same configuration.
- Keep `@ConditionalOnBean(MeterRegistry.class)` only if it does not require a fallback bean of the same type. Prefer one unconditional internal `UsageMetricsRecorder` bean that accepts `ObjectProvider<MeterRegistry>` and returns Micrometer or no-op implementation, eliminating the current two-bean missing-bean selection.
- Eliminate the generic `Clock` bean and construct `Clock.systemUTC()` inside the session-runner bean factory; internal unit tests continue injecting clocks through internal constructors.
- Keep the named `bifrostMissionExecutor` lifecycle bean because Spring must close it, and qualify every injection by that name so unrelated application `ExecutorService` beans cannot create ambiguity.
- Continue consuming an application `ObjectMapper` through `ObjectProvider` as a standard ecosystem integration, not a Bifrost SPI.

#### 2. Remove override tests and replace them with ownership tests

**Files**:

- `autoconfigure/BifrostAutoConfigurationTests.java`
- `chat/SkillAdvisorResolverTests.java`
- any test that supplies a Bifrost interface bean to trigger backoff

**Changes**:

- Delete `customChatModelResolverBacksOffDefaultConnectionConstruction()`.
- Delete or rewrite `allowsCustomSkillAdvisorResolverOverride()` and all equivalent custom-bean backoff assertions.
- Add context tests proving one framework-owned `SkillTemplate`, access guard, model resolver, advisor resolver, registries, and runtime graph are created.
- Add a negative ownership test that registers a same-type test bean and proves the context does not silently replace framework internals; ambiguity or duplicate-definition behavior must be deterministic and documented as unsupported rather than resolved through backoff.
- Preserve configuration, provider protocol, redaction, and normal context-start tests.

### Success Criteria

#### Automated Verification

- [x] `rg -n "ConditionalOnMissingBean" bifrost-spring-boot-starter/src/main/java` returns no matches.
- [x] Auto-configuration tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests,ConfigurationMetadataTest,BifrostPropertiesTest test`.
- [x] Provider and metrics tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=NamedAiConnectionRegistryTests,DefaultSkillChatModelResolverTests,MicrometerUsageMetricsRecorderTest test`.

#### Manual Verification

- [x] Every bean factory is classified as internal construction or one of the seven Application API products; none is documented or tested as application-replaceable.
- [x] Application `ObjectMapper`, `MeterRegistry`, Spring Security context, and Spring `Resource` usage remain standard integrations rather than Bifrost SPIs.

---

## Phase 4: Add Mechanical Surface, Signature, and Bean Enforcement

### Overview

Turn the closed-world classification into architecture tests that fail on any unclassified public type, leaked signature, internal consumer, or replacement seam.

### Changes Required

#### 1. Add the repository-standard architecture-test dependency

**Files**:

- root `pom.xml`
- `bifrost-spring-boot-starter/pom.xml`
- `bifrost-sample/pom.xml` if the sample consumer rule uses ArchUnit

**Changes**:

- Add ArchUnit JUnit 5 as a test dependency with its version managed in the parent.
- Do not add an executable production dependency.

#### 2. Add the complete type classification and public allowlists

**New file**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostPublicSurfaceArchitectureTest.java`

**Changes**:

- Scan every production top-level class in `com.lokiscale.bifrost`.
- Classify the exact seven API types, the four framework-integration types, `SkillExecutionEvent` as the deliberately exposed ephemeral diagnostic DTO, and every remaining type as internal implementation.
- Assert the API package contains exactly the seven approved top-level public types and no others.
- Store every technically public `.internal` type in an explicit `Map<String,String>` whose value is a nonblank reason explaining the cross-package or Spring necessity. Fail when a new public internal type is absent, when an allowlisted type no longer exists, or when its reason is blank.
- Assert all non-allowlisted internal top-level types are not public.
- Assert there is no `.spi` package and no type classified as Supported SPI.
- Reflect over public and protected fields, constructors, methods, method parameters, return types, generic arguments, record components, declared exceptions, and annotations of the seven API types. Recursively reject any Bifrost type outside `com.lokiscale.bifrost.api`.
- Assert the API package does not depend on `.internal` or `.autoconfigure` in public signatures. Private implementation references inside the internal `DefaultSkillTemplate` are permitted because it is not in the API package.

#### 3. Enforce the empty Spring replacement surface

**New file**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/architecture/BifrostAutoConfigurationBoundaryTest.java`

**Changes**:

- Reflect over every `@Bean` method and classify it as framework-owned internal construction or the framework-owned `SkillTemplate` product.
- Assert the supported override allowlist is empty.
- Assert no method or configuration class uses `@ConditionalOnMissingBean`.
- Assert Bifrost bean methods do not expose implementation types outside the separately classified integration package more widely than required.
- Assert `AccessGuard` and `SkillChatModelResolver` are internal and their default instances are framework-owned.

#### 4. Enforce representative application consumption

**New file**: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SupportedApiUsageArchitectureTest.java`

**Changes**:

- Assert sample production code imports Bifrost types only from `com.lokiscale.bifrost.api`.
- Exclude no sample production class by name.
- Keep a compile/runtime smoke test that injects `SkillTemplate`, invokes a YAML skill, uses `SkillMethod`, passes a Spring `Resource` attachment value, and observes `SkillExecutionView` without internal imports.

### Success Criteria

#### Automated Verification

- [x] Architecture tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostPublicSurfaceArchitectureTest,BifrostAutoConfigurationBoundaryTest test`.
- [x] Sample consumer boundary passes: `.\mvnw.cmd -pl bifrost-sample -am -Dtest=SupportedApiUsageArchitectureTest,SampleApplicationTests test`.
- [x] Supported-surface integration passes: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SupportedSurfaceIntegrationTest test`.
- [x] Opt-in real-provider smoke passes through `SkillTemplate`: `.\mvnw.cmd -pl bifrost-sample -am '-Dbifrost.live-provider-test=true' '-Dtest=LiveProviderSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`.
- [x] Adding a temporary public internal type, leaked public signature, `.spi` type, or `@ConditionalOnMissingBean` causes the corresponding focused test to fail; remove the temporary mutation after proving the guard.

#### Manual Verification

- [x] Failure messages identify the unclassified type, leaked signature, or bean method and tell the implementer which closed allowlist requires deliberate amendment.
- [x] Counts are reported for context but are not used as the success threshold.

---

## Phase 5: Migrate Repository Consumers and Documentation Atomically

### Overview

Update samples, tests, README guidance, skill-authoring anchors, and stale trace notes to the new closed API with no old-package compatibility layer.

### Changes Required

#### 1. Update sample production code

**Files**:

- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java`
- incident, insurance, support, and travel controllers
- all deterministic service classes using `SkillMethod`

**Changes**:

- Replace `skillapi` and `annotation` imports with `com.lokiscale.bifrost.api`.
- Replace `executionView.executionJournal()` with `executionView.events()`.
- Rename sample response keys from `executionJournal` to `executionEvents` so samples teach the supported DTO rather than an internal journal concept.
- Keep invocation inputs, result strings, resources, and manifest names unchanged.

#### 2. Rewrite sample tests around supported behavior

**Files**: controller tests, `SampleApplicationTests`, and incident/insurance/support/travel catalog tests.

**Changes**:

- Construct `SkillExecutionView` with immutable `SkillExecutionEvent` lists instead of `ExecutionJournal`.
- Remove imports of `CapabilityRegistry`, `SkillImplementationTargetRegistry`, `YamlSkillCatalog`, `YamlSkillDefinition`, and `BifrostProperties` from sample tests.
- Replace internal catalog/registry assertions with context startup, supported facade invocation, public output, and manifest-driven behavior tests. Manifest parser internals remain covered in starter tests.
- Preserve representative application compilation and execution through the public facade.

#### 3. Update developer documentation

**Files**:

- root `README.md`
- `bifrost-sample/README.md` where applicable
- `ai/thoughts/future/possible-skill-execution-projection.md`
- `ai/thoughts/future/possible-nested-execution-observability.md` if it claims durable trace compatibility

**Changes**:

- Update imports and invocation examples to `com.lokiscale.bifrost.api`.
- Add a concise closed Application API list and state that all other starter implementation types are internal before 1.0.
- State that there are currently no supported Bifrost-specific extension points or bean overrides.
- Document that `SkillTemplate` is injectable/mockable but application implementation or bean replacement is unsupported.
- Describe `SkillExecutionEvent` as a current-version trusted-development diagnostic projection that may contain business data; do not claim comprehensive sanitization.
- Document the minimal exception boundary: input validation, standard access denial, and general `SkillException`.
- Correct the future projection note's “complete, versioned forensic record” and audit/durability implications to match the canonical current-run trace policy.

#### 4. Update skill-authoring source anchors only

**Files**:

- `ai/skill-authoring/mental-model.md`
- any routed topic whose existing source link points to a moved Java path

**Changes**:

- Update the `SkillTemplate`, internal implementation, and `SkillMethod` anchors.
- Preserve existing author-facing semantics and incomplete coverage statements.
- Do not add a diagnostics or RBAC topic and do not change the README coverage table.

### Success Criteria

#### Automated Verification

- [x] No repository Java source imports removed application packages: `rg -n "com\.lokiscale\.bifrost\.(skillapi|annotation|core|runtime|chat|security|skill|vfs|linter|outputschema)" bifrost-sample --glob "*.java"` returns no matches.
- [x] README and skill-authoring links resolve to existing files.
- [x] Sample tests pass: `.\mvnw.cmd -pl bifrost-sample -am test`.
- [x] Skill-authoring guidance changed in this phase is supported by the cited production source and focused tests.

#### Manual Verification

- [x] A developer can identify every supported Java entry point from README without interpreting implementation packages.
- [x] Documentation does not call any Bifrost bean, interface, constructor, or internal package an extension point.
- [x] Documentation clearly distinguishes current-version diagnostics from durable serialized contracts.

---

## Phase 6: Full Boundary and Current-Version Coherence Verification

### Overview

Run the complete Java and CLI suites, audit every acceptance criterion, and verify that no old public shape or replacement path survived the atomic change.

### Changes Required

#### 1. Audit source and Spring surfaces

**Files**: entire repository.

**Changes**:

- Generate the final production type inventory from the architecture test and review each externally accessible entry's category and reason.
- Generate the bean inventory from `BifrostAutoConfiguration` and confirm every bean is framework-owned and the override allowlist is empty.
- Search for old package imports, old journal DTO use, public runtime exceptions, custom resolver/advisor overrides, `@ConditionalOnMissingBean`, compatibility constructors, deprecated aliases, and duplicate paths.
- Verify the public-surface delta matches the ticket and plan rather than only reducing a count.

#### 2. Verify current-version trace coherence

**Files**: trace writer/reader/projector tests and `bifrost-cli`.

**Changes**:

- Run writer, reader, handle, contract, boundary-cleanup, and projection tests.
- Run CLI Go tests against the current trace fixture.
- Do not add a historical fixture reader or schema adapter if a fixture becomes stale; update or remove current-version fixtures atomically only if the implementation actually changes their current representation.

### Success Criteria

#### Automated Verification

- [x] Full Maven suite passes: `.\mvnw.cmd test`.
- [x] CLI tests pass: run `go test ./...` from `bifrost-cli`.
- [x] Trace suite passes: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=NdjsonTraceRecordWriterTest,NdjsonExecutionTraceReaderTest,ExecutionTraceHandleTest,ExecutionTraceContractTest,ExecutionTraceBoundaryCleanupTest,ExecutionJournalProjectorTest,ExecutionJournalProjectionContractTest test`.
- [x] `rg -n "ConditionalOnMissingBean" bifrost-spring-boot-starter/src/main/java` returns no matches.
- [x] `rg -n "com\.lokiscale\.bifrost\.(skillapi|annotation)" --glob "*.java" .` returns no matches.
- [x] `git diff --check` reports no whitespace errors.

#### Manual Verification

- [x] Every ticket acceptance criterion maps to a test, an explicit allowlist entry, or a reviewed documentation change.
- [x] There are zero unclassified public types, zero supported SPIs, zero Bifrost bean replacement points, and zero compatibility shims.
- [x] Normal invocation, mapped Java targets, validation, authorization, attachment values, current diagnostics, provider routing, and configuration still work through the supported surface.

## Testing Strategy

Create a dedicated testing plan with `ai/commands/3_testing_plan.md` before implementation. It should order the work around the first failing boundary tests and include at least:

### Unit Tests

- Defensive immutability of `SkillExecutionView`, `SkillExecutionEvent`, and `SkillInputValidationIssue` lists/maps.
- Translation from internal validation issues and journal entries to public DTOs.
- `SkillTemplate` authentication capture, exception normalization, and success-only observer behavior.
- Package-private internal behavior after test package relocation.

### Architecture and Contract Tests

- Exact seven-type API allowlist.
- Four-type framework-integration allowlist.
- Explicit technically-public internal map with nonblank reasons.
- Recursive public-signature leak detection.
- Empty SPI and bean-override allowlists.
- Absence of `@ConditionalOnMissingBean`.
- Sample production dependency only on `com.lokiscale.bifrost.api`.

### Integration Tests

- Auto-configuration creates the complete framework-owned graph.
- `SkillTemplate` invokes YAML skills with object/map and Spring `Resource` inputs.
- Current authentication authorizes or denies root and nested RBAC consistently.
- Model connection, metrics, advisor, trace, and provider behavior remains internally coherent.
- Samples compile, start, and return `executionEvents` through the public DTOs.

### Manual Testing Steps

1. Start the sample application and invoke one direct skill and one planning/nested skill through their existing endpoints.
2. Verify successful responses contain session ID and `executionEvents`, with the same categories of projected information as the former journal.
3. Invoke a protected skill with matching and missing Spring Security authorities and verify success versus `AccessDeniedException` handling without an authentication input argument.
4. Trigger invalid input and confirm callers see `SkillInputValidationException` with path/code/message only.
5. Trigger a provider or execution failure and confirm callers see a safe `SkillException` while logs retain the internal cause.
6. Attempt to add a custom `SkillChatModelResolver`, `AccessGuard`, or `SkillTemplate` bean and confirm no documented or conditional backoff path exists.

## Performance Considerations

- Package and visibility changes have no runtime cost.
- Diagnostic translation adds one deep immutable copy at successful completion, replacing the existing journal DTO exposure. Keep it linear in the selected journal size and do not read the raw trace a second time.
- Authentication lookup through `SecurityContextHolder` occurs once per root facade invocation; nested execution reuses the session identity.
- Deterministic bean construction must preserve one reusable `ChatModel` per named connection and the current virtual-thread executor lifecycle.
- Architecture tests add test-time class scanning only and no production dependency.

## Migration Notes

This is an intentional pre-1.0 source break with no external consumers and no compatibility period.

- Replace all old imports with `com.lokiscale.bifrost.api` in the same change.
- Replace `SkillExecutionView.executionJournal()` with `events()` and update sample JSON keys to `executionEvents`.
- Replace runtime validation issue access with `SkillInputValidationIssue.path()`, `code()`, and `message()`; `rejectedValue` is removed.
- Stop defining or testing replacement Bifrost beans. There are no supported replacements.
- Do not publish old-package forwarding types, deprecated aliases, overloads, bean names, or adapters.
- Configuration files, YAML manifests, trace persistence settings, and data stores require no migration.

## References

- Original ticket: `ai/thoughts/tickets/eng-reduce-spring-boot-starter-public-surface.md`
- Canonical compatibility policy: `ai/thoughts/framework-feature-design-lens.md`
- Planning command: `ai/commands/2_create_plan.md`
- Testing-plan command: `ai/commands/3_testing_plan.md`
- Testing plan: `ai/thoughts/plans/2026-07-15-reduce-spring-boot-starter-public-surface-testing.md`
- Current facade: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/api/SkillTemplate.java`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/skillapi/DefaultSkillTemplate.java`, and `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/api/SkillExecutionView.java`
- Current auto-configuration: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- Current diagnostic projection: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/runtime/trace/ExecutionJournalProjector.java`
- Current RBAC implementation: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/security/AccessGuard.java` and `DefaultAccessGuard.java`
- Current internal model routing: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/internal/chat/SkillChatModelResolver.java`
- Planning metadata: 2026-07-15 16:43:11 PDT; commit `2a690417cdec638031b33518f4224e21cb28dbe5`; branch `main`; repository `bifrost`
