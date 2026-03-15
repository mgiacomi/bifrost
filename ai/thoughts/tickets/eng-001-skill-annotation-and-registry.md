# Ticket: eng-001-skill-annotation-and-registry.md
## Issue: Implement @SkillMethod Annotation and CapabilityRegistry

### Context
Phase 2 (Core Engine and Spring AI Integration) requires a mechanism to discover custom Java skills and register them for use by the Bifröst engine.

### Requirements
1. **`@SkillMethod` Annotation:**
   - Create a single target annotation `@Target({ElementType.METHOD})` and `@Retention(RetentionPolicy.RUNTIME)`.
   - Fields should include:
     - `name` (String, default empty: if empty, derive from method name)
     - `description` (String, required)
     - `modelPreference` (Enum `LIGHT`, `HEAVY`, default to `LIGHT`)
2. **`CapabilityRegistry` Interface & Implementation:**
   - Create an interface `CapabilityRegistry`.
   - Methods:
     - `void register(String capabilityName, CapabilityMetadata metadata)`
     - `CapabilityMetadata getCapability(String name)`
     - `List<CapabilityMetadata> getAllCapabilities()`
   - An in-memory concurrent map implementation `InMemoryCapabilityRegistry`.

### Acceptance Criteria
- `@SkillMethod` cleanly compiles.
- `CapabilityRegistry` and `InMemoryCapabilityRegistry` interfaces/classes are implemented.
- Added appropriate unit tests confirming basic add/get/list operations in the registry in a thread-safe manner.
