# SkillMethod BeanPostProcessor Testing Plan

## Change Summary
- Implementing `SkillMethodBeanPostProcessor` to intercept Spring beans, discover `@SkillMethod` annotated methods, and register them as executable Spring AI tools within the `CapabilityRegistry`.
- Leveraging Spring AI's native `MethodToolCallback` for argument binding and JSON schema discovery utilizing Jackson `ObjectMapper` for Map to JSON serialization.

## Impacted Areas
- `bifrost-spring-boot-starter`: `pom.xml` (Dependencies)
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java` (New)

## Risk Assessment
- **Component Scanning**: The BeanPostProcessor might inadvertently crash the Spring application context if it mishandles proxy beans or reflection errors during scanning.
- **Parameter Mapping logic**: Mapping incoming `Map<String, Object>` envelopes to method parameters must perfectly bridge to the generated JSON Schema from Spring AI. If Jackson deserialization fails, or Spring AI throws an exception internally for specific method signatures or primitive types, it crashes the skill execution.
- **Dependency Conflicts**: Dropping `spring-ai-model` onto the classpath might cause unexpected conflicts if versions differ from established Spring Boot parents (though `spring-ai-bom` helps circumvent this).

## Existing Test Coverage
- Foundational domain tests like `InMemoryCapabilityRegistryTest` exist.
- No tests currently cover `@SkillMethod` interception because the processor does not exist yet.

## Bug Reproduction / Failing Test First
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- Arrange/Act/Assert outline:
  - Create a `CapabilityRegistry` instance.
  - Create an instance of `SkillMethodBeanPostProcessor`.
  - Pass a dummy bean with an `@SkillMethod` annotated method into `postProcessAfterInitialization`.
  - Assert that `capabilityRegistry.getCapability()` returns the correctly configured capability.
- Expected failure (pre-fix): Missing `SkillMethodBeanPostProcessor` prevents compilation. Compilation must pass through Phase 1 of the implementation plan, and the test will fail until Phase 2 actually wires the registration.

## Tests to Add/Update
### 1) Should Register Bean Method As Capability
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- What it proves: A simple annotated method is discovered, encapsulated, and registered to the mock `CapabilityRegistry`.
- Fixtures/data: A local class `DummyBean` with `@SkillMethod(name = "testOperation", description = "Test desc")`.
- Mocks: `InMemoryCapabilityRegistry`.

### 2) Should Invoke Capability Using Envelope Map
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- What it proves: Calling the `CapabilityInvoker` with a `Map<String, Object>` correctly delegates to the underlying method and parses arguments seamlessly via Spring AI's native binding.
- Fixtures/data: `DummyBean` with a method taking multiple arguments (e.g., `String param1`, `Boolean param2`).
- Mocks: None needed internally, assert the String return from the invoker matches expectations.

### 3) Should Handle Parameter Defaulting / Nulls Gracefully
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- What it proves: If the Envelope Map misses an optional parameter, Spring AI handles the method invocation natively without crashing.
- Fixtures/data: Nullable or `@ToolParam` method annotations where an argument is excluded in the invocation Map.
- Mocks: None needed.

### 4) Spring Context Loads Successfully
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorIntegrationTest.java` (Optional, depending on existing test suites)
- What it proves: Application context loads without throwing `BeansException` when introducing the `SkillMethodBeanPostProcessor` into the lifecycle. 

## How to Run
- `mvn clean compile -pl bifrost-spring-boot-starter`
- `mvn test -pl bifrost-spring-boot-starter`

## Exit Criteria
- [ ] Failing test exists (compilation/red test phase).
- [x] All tests pass post-fix.
- [x] New/updated tests cover the changed behavior, specifically the reflection and invocation mapping.
- [ ] Manual verification context tests pass without interference from proxies.
