# ENG-007 ExecutionCoordinator Implementation Plan

## Overview

Implement the first end-to-end `ExecutionCoordinator` loop for Bifrost so YAML-defined LLM skills can execute through Spring AI using validated execution metadata, session-aware planning state, session-local `ref://` pointer resolution, and YAML-only capability discovery filtered by RBAC and `allowed_skills`.

## Current State Analysis

The codebase already contains the supporting primitives for ENG-007, but not the coordinating runtime. `BifrostAutoConfiguration` wires the registry, session runner, YAML skill catalog/registrar, and provider-aware `SkillChatClientFactory` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:25`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfiguration.java#L25)). `BifrostSession` already manages execution frames and a serializable `ExecutionJournal`, but it has no first-class planning state ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:15`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\BifrostSession.java#L15)).

YAML skills already resolve boot-time execution metadata into `EffectiveSkillExecutionConfiguration` and `SkillExecutionDescriptor` ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:92`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCatalog.java#L92), [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillExecutionDescriptor.java:19`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\SkillExecutionDescriptor.java#L19)). The current YAML invoker only supports `mapping.target_id` delegation to discovered Java targets and explicitly throws for LLM-backed YAML execution when no target id is present ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCapabilityRegistrar.java#L39)).

`SkillMethodBeanPostProcessor` already builds Spring AI tool definitions and wraps Java invocations behind `CapabilityInvoker`, which is the existing deterministic execution boundary ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:54`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\SkillMethodBeanPostProcessor.java#L54)). `SpringAiSkillChatClientFactory` already creates provider-specific `ChatClient` instances from validated skill execution settings ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java:36`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\chat\SpringAiSkillChatClientFactory.java#L36)).

## Desired End State

After this plan is complete, Bifrost can execute a YAML-defined LLM skill through a dedicated `ExecutionCoordinator` that:

- stores the active flight plan as structured session state
- journals structured plan updates for audit and reconstruction
- resolves strict `ref://...` scalar inputs against a session-scoped VFS abstraction
- exposes only YAML-facing skills to the LLM
- filters YAML-visible skills through RBAC plus `allowed_skills`
- uses boot-validated `EffectiveSkillExecutionConfiguration` to create the `ChatClient`
- loops safely within session stack limits and integration-test coverage

Verification comes from new unit and integration tests covering planning-state lifecycle, strict `ref://` resolution, YAML-only discovery, and the end-to-end coordinator loop.

### Key Discoveries:
- `BifrostSession` already provides thread-safe frame and journal management but no plan aggregate today ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:57`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\BifrostSession.java#L57)).
- YAML skills already carry resolved provider/model/thinking configuration, so the coordinator should consume that directly rather than recomputing it ([`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/EffectiveSkillExecutionConfiguration.java:6`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\EffectiveSkillExecutionConfiguration.java#L6)).
- Raw Java `@SkillMethod` targets are implementation-level capabilities today; YAML skills are the intended user-facing contract ([`ai/thoughts/phases/README.md:27`](C:\opendev\code\bifrost\ai\thoughts\phases\README.md#L27)).
- The current codebase has no `ExecutionCoordinator`, no `ref://` resolver, and no source-level planning-mode implementation ([`ai/thoughts/research/2026-03-15-ENG-007-execution-coordinator-current-state.md:26`](C:\opendev\code\bifrost\ai\thoughts\research\2026-03-15-ENG-007-execution-coordinator-current-state.md#L26)).

## What We're NOT Doing

- Implementing tag-based skill discovery in this iteration
- Introducing globally visible skills
- Exposing raw discovered `@SkillMethod` capabilities directly to the LLM tool list
- Building non-filesystem VFS backends such as S3 or Azure Blob in this iteration
- Implementing advanced RBAC/tag ranking UX beyond pre-exposure filtering and execution-time enforcement
- Implementing `readData` and `writeData` as a full user-facing product surface beyond the abstractions needed for `ref://` handling

## Implementation Approach

Add a dedicated execution layer that sits between YAML skill metadata and Spring AI runtime calls. The coordinator should work against typed session state and typed helper abstractions rather than raw maps or JSON blobs wherever mutable runtime state is involved. Planning state should be modeled as a session aggregate and journaled separately as structured snapshots or delta payloads.

For `ref://`, define a session-scoped VFS abstraction with opaque refs and strict resolver semantics. The coordinator resolves only exact scalar `ref://...` argument values before invoking deterministic targets. This keeps the pointer contract storage-agnostic and protects later migration to non-filesystem VFS implementations.

For discovery, treat YAML capabilities as the LLM-facing tool surface. Add `allowed_skills` to the YAML manifest model and compute the visible capability set from the current YAML skill, constrained first by YAML declarations and RBAC, then mapped to Spring AI tools.

## Phase 1: Session Planning State

### Overview
Introduce first-class planning state into `BifrostSession` so the coordinator has a typed place to store and update the active flight plan while journaling corresponding plan snapshots.

### Changes Required:

#### 1. Planning domain model
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/` new plan-related types
**Changes**: Add typed domain objects for the active execution plan.

```java
public record ExecutionPlan(
        String planId,
        String capabilityName,
        Instant createdAt,
        List<PlanTask> tasks) {
}

public record PlanTask(
        String taskId,
        String title,
        PlanTaskStatus status,
        @Nullable String note) {
}

public enum PlanTaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    BLOCKED
}
```

#### 2. Session integration
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
**Changes**: Add an optional active plan field, accessors, and update methods that keep the session state thread-safe alongside existing frame/journal state.

```java
private ExecutionPlan executionPlan;

public Optional<ExecutionPlan> getExecutionPlan() { ... }

public void replaceExecutionPlan(ExecutionPlan plan) { ... }

public void clearExecutionPlan() { ... }
```

#### 3. Journal support for plan snapshots
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/JournalEntryType.java`
**Changes**: Add plan-specific journal entry types so the coordinator can distinguish plan creation and plan updates from generic thoughts.

```java
PLAN_CREATED,
PLAN_UPDATED,
```

### Success Criteria:

#### Automated Verification:
- [x] Starter module compiles: `./mvnw -pl bifrost-spring-boot-starter test`
- [x] Session serialization tests pass with plan state present: `./mvnw -pl bifrost-spring-boot-starter -Dtest=BifrostSessionTest,BifrostSessionJsonTest test`
- [x] New plan-state unit tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionPlanTest,BifrostSessionTest test`

#### Manual Verification:
- [ ] Plan state can be described clearly from the JSON form of a serialized session
- [ ] Journal entries clearly show plan creation and plan updates as separate history from the active session state

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 2: YAML Discovery Contract

### Overview
Define the MVP discovery surface so only YAML-facing skills are presented to the LLM, filtered by explicit `allowed_skills` plus RBAC.

### Changes Required:

#### 1. YAML manifest model updates
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
**Changes**: Add an `allowed_skills` field to the manifest model and keep parsing tolerant of missing values.

```java
@JsonProperty("allowed_skills")
private List<String> allowedSkills = List.of();
```

#### 2. YAML definition surface
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java`
**Changes**: Preserve access to the manifest-level allowed-skill declarations so the coordinator can build the tool surface from the active YAML capability.

#### 3. Visibility service
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/` new visibility service
**Changes**: Add a helper that, given the current YAML skill and current principal/roles, returns the visible YAML capabilities. This service should exclude non-YAML implementation targets from LLM exposure.

```java
public interface SkillVisibilityResolver {
    List<CapabilityMetadata> visibleSkillsFor(String currentSkillName, Authentication authentication);
}
```

### Success Criteria:

#### Automated Verification:
- [x] YAML parsing tests pass with `allowed_skills`: `./mvnw -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] New visibility resolver tests pass for allowed and disallowed skills: `./mvnw -pl bifrost-spring-boot-starter -Dtest=SkillVisibilityResolverTest test`
- [x] Auto-configuration tests still pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`

#### Manual Verification:
- [ ] Reviewing a sample manifest makes the intended visible sub-skill set obvious
- [ ] No raw `@SkillMethod` implementation target appears in the intended LLM-facing surface

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 3: Session-Scoped `ref://` Resolution

### Overview
Create the minimal VFS abstraction and strict `ref://` resolver needed for the coordinator to dereference opaque session-local pointers before deterministic tool invocation.

### Changes Required:

#### 1. VFS abstraction
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/` new types
**Changes**: Add a storage-agnostic abstraction for resolving opaque refs within a session.

```java
public interface VirtualFileSystem {
    Resource resolve(BifrostSession session, String ref);
}
```

#### 2. Ref resolver
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/` new resolver
**Changes**: Add strict matching logic that only resolves exact scalar `ref://...` strings and loads their content using Spring `Resource`.

```java
public interface RefResolver {
    Object resolveArgument(Object value, BifrostSession session);
}
```

#### 3. Default filesystem-backed MVP
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/` new default implementation
**Changes**: Provide a temp-dir or session-root-backed implementation for the MVP while keeping the ref syntax opaque.

### Success Criteria:

#### Automated Verification:
- [x] Ref resolver unit tests pass for strict-match behavior: `./mvnw -pl bifrost-spring-boot-starter -Dtest=RefResolverTest test`
- [x] VFS tests pass for session isolation: `./mvnw -pl bifrost-spring-boot-starter -Dtest=VirtualFileSystemTest test`
- [x] Existing deterministic invocation tests continue to pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests,SkillMethodBeanPostProcessorTest test`

#### Manual Verification:
- [ ] A session-local ref can be described without exposing backend storage details
- [ ] Non-ref strings remain untouched by the resolver

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 4: ExecutionCoordinator Loop

### Overview
Implement the coordinator itself: consume validated execution metadata, build the YAML-visible tool surface, trigger planning mode, resolve refs before deterministic calls, and execute the Spring AI loop.

### Changes Required:

#### 1. Execution coordinator service
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
**Changes**: Add the main runtime service that:

- accepts the current YAML skill and mission objective
- pushes/pops `ExecutionFrame`
- creates the skill-specific `ChatClient`
- requests and stores the initial flight plan when planning mode is active
- updates plan task statuses in session state and journal
- attaches only visible YAML tools to the model loop

#### 2. Tool mapping adapter
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/` new tool adapter
**Changes**: Add the bridge that turns filtered `CapabilityMetadata` entries into Spring AI tool callbacks without exposing raw internal implementation targets.

#### 3. Deterministic invocation integration
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/` coordinator support classes
**Changes**: Ensure tool invocations route through the ref resolver before calling deterministic `CapabilityInvoker` targets.

#### 4. Auto-configuration wiring
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**: Register the coordinator, visibility resolver, VFS implementation, and ref resolver as infrastructure beans.

### Success Criteria:

#### Automated Verification:
- [x] Coordinator unit tests pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorTest test`
- [x] Chat-client factory tests still pass: `./mvnw -pl bifrost-spring-boot-starter -Dtest=SpringAiSkillChatClientFactoryTests test`
- [x] Full starter test suite passes: `./mvnw -pl bifrost-spring-boot-starter test`

#### Manual Verification:
- [ ] The coordinator flow is understandable from code and session/journal state
- [ ] The LLM-facing tool set for a YAML skill is meaningfully smaller than the full internal registry

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 5: End-to-End Integration Coverage

### Overview
Prove the intended ENG-007 runtime behavior with an integration test that exercises planning mode, tool looping, strict `ref://` resolution, and max-depth-safe unwind.

### Changes Required:

#### 1. Integration test fixture
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/` new integration test
**Changes**: Create an application-context-backed test that:

- initializes a session
- invokes a YAML-defined LLM skill with a hello-world objective
- uses a mocked or fake `ChatClient` loop
- verifies initial plan creation in typed session state
- verifies plan snapshots in the journal
- verifies strict `ref://` dereferencing
- verifies only YAML-visible allowed skills are exposed
- verifies stack unwind without `MAX_DEPTH` violation

### Success Criteria:

#### Automated Verification:
- [x] New end-to-end integration test passes: `./mvnw -pl bifrost-spring-boot-starter -Dtest=ExecutionCoordinatorIntegrationTest test`
- [x] Full repository tests pass: `./mvnw test`

#### Manual Verification:
- [ ] The integration test scenario clearly demonstrates the intended coordinator lifecycle
- [ ] The final session snapshot and journal are sufficient to reconstruct what happened during the mission

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

## Testing Strategy

### Unit Tests:
- `BifrostSession` plan-state lifecycle and serialization
- plan snapshot journaling behavior
- YAML manifest parsing for `allowed_skills`
- visibility resolution for YAML-only exposure
- strict `ref://` scalar resolution rules
- coordinator behavior for planning-mode initialization and plan updates

### Integration Tests:
- full coordinator execution from YAML skill metadata through Spring AI tool loop
- coordinator behavior with deterministic delegated skills and session-local refs
- capability exposure filtering by `allowed_skills` and RBAC

**Note**: Create a dedicated testing plan via `/testing_plan` before implementation for full impacted-area coverage and command sequencing.

### Manual Testing Steps:
1. Start from a YAML skill that allows one sub-skill and verify the intended tool surface is limited to that declared skill.
2. Execute a coordinator flow that produces a flight plan and confirm the active session state and journal both reflect it.
3. Pass a strict `ref://...` argument into a deterministic delegated skill and verify resolution occurs without exposing backend storage details.

## Performance Considerations

- Keep the visible tool set small by design through explicit `allowed_skills`.
- Avoid repeated model-resolution work by consuming the already validated `EffectiveSkillExecutionConfiguration`.
- Keep `ref://` resolution strict and scalar-only for the MVP to avoid recursive deep traversal costs and ambiguous partial substitutions.

## Migration Notes

- Existing deterministic YAML-to-`@SkillMethod` behavior should remain valid.
- YAML manifests without `allowed_skills` should default to no non-self sub-skill visibility unless explicitly configured otherwise.
- The VFS abstraction should be introduced behind interfaces so future storage adapters can be added without changing the `ref://` contract.

## References

- Original ticket: [`ai/thoughts/tickets/eng-007-execution-coordinator.md`](C:\opendev\code\bifrost\ai\thoughts\tickets\eng-007-execution-coordinator.md)
- Related research: [`ai/thoughts/research/2026-03-15-ENG-007-execution-coordinator-current-state.md`](C:\opendev\code\bifrost\ai\thoughts\research\2026-03-15-ENG-007-execution-coordinator-current-state.md)
- Current auto-configuration: [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:25`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\autoconfigure\BifrostAutoConfiguration.java#L25)
- Current session model: [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:15`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\core\BifrostSession.java#L15)
- Current YAML execution registration: [`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:25`](C:\opendev\code\bifrost\bifrost-spring-boot-starter\src\main\java\com\lokiscale\bifrost\skill\YamlSkillCapabilityRegistrar.java#L25)
