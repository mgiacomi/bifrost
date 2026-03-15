# Skill Annotation and Registry Implementation Plan

## Overview

Implementing the core engine's capability discovery mechanisms for Phase 2 of the Bifrost project. This includes a `@SkillMethod` annotation for method discovery, `CapabilityMetadata` structure tracking, and a thread-safe `InMemoryCapabilityRegistry` implementation under the `CapabilityRegistry` interface to support auto-discovery of Java skills by the engine.

## Current State Analysis

The project structure is in place (`bifrost-spring-boot-starter` and `bifrost-sample`), but the core framework under `com.lokiscale.bifrost` lacks the semantic discovery pieces. `bifrost-spring-boot-starter` will house these primitives.

## Desired End State

A functional registry and annotation system where Java capability metadata can be stored securely and retrieved thread-safely by the execution coordinator (to be built later). The module will contain the functional interfaces to easily map Java methods to execution paths via an `Invoke` strategy suitable for bridging with Spring AI.

### Key Discoveries:
- Context mapping requirement: `CapabilityMetadata` requires a physical `id` (e.g. `beanName#methodName`) to ensure deep debugging in the journal, along with an alpha-numeric `name` (the Opcode) solely for LLM tool invocation.
- Ambiguity is a Kernel Panic: Duplicate skill `names` must fail-fast actively throwing `CapabilityCollisionException`.
- The registry needs thread-safety since the BifrostSession might push concurrent skill resolution frames.
- A functional interface (`CapabilityInvoker`) abstraction keeps the registry disconnected from complex reflection APIs down the line. It serves as the data bus that handles Map->Java type conversions.
- A decoupled `ModelPreference` enum ensures cleaner imports across domains.

## What We're NOT Doing

- We are NOT implementing the Spring Bean auto-discovery (`BeanPostProcessor` class) in this phase. That's for the subsequent phase or ticket.
- We are NOT hooking up Spring AI `ChatClient` connections here.
- We are NOT doing full error handling and translation (the exception transformer).

## Implementation Approach

Build the core structures bottom-up to assure testability. First the interfaces and annotations, then the data structures (`CapabilityMetadata`), and finally the map-based registry implementation.

## Phase 1: Core API & Annotation

### Overview
Draft the foundational enum, functional interface, and annotation markers required.

### Changes Required:

#### 1. Core Enumeration & Exceptions
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ModelPreference.java`
**Changes**: Define the `ModelPreference` enum with `LIGHT` and `HEAVY`.
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityCollisionException.java`
**Changes**: A `RuntimeException` thrown when a skill name conflict is encountered.

#### 2. Functional Interface for Invocation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityInvoker.java`
**Changes**: Define `@FunctionalInterface public interface CapabilityInvoker`. Expectation is this provides a binding bridge converting `Map<String, Object>` args into actual Java inputs.

#### 3. Agent Skill Annotation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/annotation/SkillMethod.java`
**Changes**: Create `@Target({ElementType.METHOD})` and `@Retention(RetentionPolicy.RUNTIME)` annotation containing `name`, `description`, and `modelPreference`.

### Success Criteria:

#### Automated Verification:
- [x] Maven `clean compile` passes cleanly in the starter module: `mvn clean compile -pl bifrost-spring-boot-starter`

#### Manual Verification:
- [ ] Code properly packages and formatting adheres to Java standards.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Registry Infrastructure

### Overview
Build the metadata record holding state and the registry interfaces maintaining engine capabilities.

### Changes Required:

#### 1. Capability Metadata Structure
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityMetadata.java`
**Changes**: Create a `record` to hold `id` (stable physical ID format: `beanName#methodName`), `name` (the LLM opcode), `description`, `ModelPreference`, `rbacRoles`, and the core `CapabilityInvoker`.

#### 2. Registry Interface
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityRegistry.java`
**Changes**: Create the interface with `register`, `getCapability`, and `getAllCapabilities` methods.

#### 3. In-Memory Registry Implementation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistry.java`
**Changes**: Implement `CapabilityRegistry` backing it with a `ConcurrentHashMap` for fast, thread-safe access. The registry uses `name` as the primary key ($O(1)$ lookup). It MUST throw `CapabilityCollisionException` on duplicate names.

### Success Criteria:

#### Automated Verification:
- [x] Project build succeeds: `mvn clean compile -pl bifrost-spring-boot-starter`

#### Manual Verification:
- [ ] Design patterns conceptually verified.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Unit Testing

### Overview
Add isolated tests to ensure the integrity of the data structures and thread-safety of the registry.

### Changes Required:

#### 1. Test Suite for Memory Registry
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java`
**Changes**: Create multithreaded and basic unit tests utilizing JUnit 5 ensuring standard registry behaviors (`getCapability`, `register`, list operations) function properly.

### Success Criteria:

#### Automated Verification:
- [x] All unit tests pass: `mvn test -pl bifrost-spring-boot-starter`

#### Manual Verification:
- [ ] Review test adequacy against potential concurrent additions by an ApplicationContext loaded parallelly during Spring bootup.

---

## Testing Strategy

### Unit Tests:
- Ensure `InMemoryCapabilityRegistry` handles overlapping inserts gracefully.
- Ensure metadata parameters are reliably extracted.

### Integration Tests:
- (Out of scope for this plan, validation done at the generic Spring level later).

### Manual Testing Steps:
1. Inspect the test suite run outputs ensuring they complete swiftly.
2. Review that `ModelPreference` enforces defaults appropriately where omitted.

## Performance Considerations

- `ConcurrentHashMap` ensures performance isn't bottlenecked during discovery scans mapping capabilities across hundreds of beans. The memory footprint of the record is tiny.

## References

- Original ticket: `ai/thoughts/tickets/eng-001-skill-annotation-and-registry.md`
