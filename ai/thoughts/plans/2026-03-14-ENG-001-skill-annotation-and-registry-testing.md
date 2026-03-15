# Skill Annotation and Registry Testing Plan

## Change Summary
- Implementing `@SkillMethod` annotation for autodiscovering Spring bean methods as engine capabilities.
- Implementing `CapabilityMetadata` to store capability details and functional references via a `CapabilityInvoker` functional interface.
- Implementing an `InMemoryCapabilityRegistry` to store and retrieve capabilities in a thread-safe manner using `ConcurrentHashMap`.
- Defining `ModelPreference` enum for capability routing metadata (`LIGHT`, `HEAVY`).

## Impacted Areas
- `bifrost-spring-boot-starter` core modules (`com.lokiscale.bifrost.core.*` and `com.lokiscale.bifrost.annotation.*`).
- This is net new functionality, so it does not directly impact existing code paths. However, the thread safety of the registry is critical for future features.

## Risk Assessment
- **High-risk behaviors:** Concurrent read/write operations on the capability registry during application initialization or parallel request handling.
- **Edge cases:** Registering multiple capabilities with the same name. Finding duplicate names must actively throw a `CapabilityCollisionException` to avoid ambiguity for the agent. Retrieving non-existent capabilities.
- **Dependency integrity:** Ensuring these core primitives stay decoupled from heavier frameworks like Spring AI until the execution coordinator is built.

## Existing Test Coverage
- **Relevant existing tests:** None yet for this specific feature.
- **Gaps:** 100% gap for these new classes.

## Bug Reproduction / Failing Test First
*Note: As this is a greenfield feature rather than a bug fix, there is no existing failing test.* 
*We will start by writing tests for the expected behavior of the registry before moving to its implementation (TDD style).*

- Type: Unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java`
- Arrange/Act/Assert outline:
  - Arrange: Instantiate an empty `InMemoryCapabilityRegistry`. Try to retrieve randomly named capability.
  - Act: Call `getCapability("fake")`.
  - Assert: Verify `null` (or an appropriate `Optional.empty()` if we choose, though the spec says `CapabilityMetadata`) is returned without throwing unhandled exceptions.

## Tests to Add/Update

### 1) Basic Registration and Retrieval
- Type: Unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java`
- What it proves: A capability can be registered and subsequently retrieved accurately by ID/name.
- Fixtures/data: Dummy `CapabilityMetadata` records representing simple skills (e.g., `Calculator.add()`).
- Mocks: Minimal; we can implement a dummy `CapabilityInvoker` lambda.

### 2) Overwrite / Duplicate Handling
- Type: Unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java`
- What it proves: The registry behaves predictably and fails-fast throwing `CapabilityCollisionException` when a duplicate capability name is registered.
- Fixtures/data: Two dummy `CapabilityMetadata` records with the exact same name.
- Mocks: None.

### 3) Concurrent Access (Thread Safety)
- Type: Unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/InMemoryCapabilityRegistryTest.java`
- What it proves: The `ConcurrentHashMap` backing the registry ensures no `ConcurrentModificationException` occurs or data is lost when multiple threads write and read simultaneously.
- Fixtures/data: A multithreaded `ExecutorService` loop registering 1000+ unique metadata objects simultaneously while another thread repeatedly calls `getAllCapabilities()`.
- Mocks: None.

### 4) Annotation Meta-Data Inspection 
*(Optional validation if reflection inspection utilities are introduced, otherwise purely compile-time check)*
- Type: Unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/annotation/SkillMethodTest.java`
- What it proves: `@SkillMethod` retains its properties at `RUNTIME` and default values are respected (`ModelPreference.LIGHT`, empty name string).
- Fixtures/data: A dummy class with methods annotated with `@SkillMethod`.
- Mocks: None.

## How to Run
- Build command: `mvn clean compile -pl bifrost-spring-boot-starter`
- Test command(s): `mvn test -pl bifrost-spring-boot-starter -Dtest="*CapabilityRegistryTest, *SkillMethodTest"`

## Exit Criteria
- [x] Ensure all unit tests covering basic CRUD operations on the registry pass.
- [x] Ensure the concurrency test passes reliably across multiple executions without deadlocks or data loss.
- [x] Verify `CapabilityInvoker` can successfully execute a simple lambda function through the metadata wrapper in the test context.
- [x] Run `mvn test` cleanly on the starter module.
