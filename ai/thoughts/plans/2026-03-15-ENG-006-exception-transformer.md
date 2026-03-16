# ENG-006 Exception Transformer Implementation Plan

## Overview

Implement a Bifrost-owned exception transformation boundary for deterministic skill execution so `@SkillMethod` failures return a clean AI-readable error string instead of surfacing raw Java stack traces into the LLM tool payload.

## Current State Analysis

`SkillMethodBeanPostProcessor` is the only Bifrost-owned execution boundary for discovered `@SkillMethod` capabilities today. It creates the registry invoker and delegates to Spring AI's `MethodToolCallback.call(...)`, but it currently wraps execution failures in `IllegalStateException` rather than returning a transformed result (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:49-80`).

YAML deterministic skills with `mapping.target_id` do not have a separate execution layer. They resolve the discovered capability and reuse its `invoker()` directly, so any change at `invokeToolCallback(...)` automatically applies to both direct method execution and YAML-mapped deterministic execution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39-48`).

The current test suite only covers successful method invocation for direct and YAML-mapped paths; there is no failure-path assertion for transformed payloads or logging (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:36-70`, `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:106-120`).

## Desired End State

Deterministic tool execution in Bifrost catches framework-visible `@SkillMethod` failures at the shared invocation boundary, logs the real exception with stack trace through SLF4J, unwraps nested causes to the underlying business failure when formatting the payload, and returns a sanitized string in the form `ERROR: [ExceptionClass]. HINT: [message]` as the tool result. This behavior is available through both direct discovered capabilities and YAML deterministic skills that delegate through `mapping.target_id`.

### Key Discoveries:
- `SkillMethodBeanPostProcessor` constructs the `CapabilityInvoker` lambda and owns the shared callback boundary, making it the narrowest implementation point for this iteration (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:49-80`).
- YAML mapped skills reuse `target.invoker()` rather than wrapping it, so no separate YAML exception hook is needed if the shared invoker is updated (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:41-48`).
- Auto-configuration currently instantiates `SkillMethodBeanPostProcessor` directly with only a `CapabilityRegistry`, so introducing a transformer as an injectable strategy will require a corresponding infrastructure bean update (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:39-44`).
- Spring Boot test support is already available through `spring-boot-starter-test`, so log capture can be verified without adding new test dependencies (`bifrost-spring-boot-starter/pom.xml:55-64`).

## What We're NOT Doing

- We are not introducing the future `ExecutionCoordinator`; this iteration stays at the existing `SkillMethodBeanPostProcessor.invokeToolCallback(...)` boundary.
- We are not changing unsupported YAML-only LLM-backed execution, which still throws `UnsupportedOperationException` when `mapping.target_id` is absent (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:50-53`).
- We are not depending on undocumented Spring AI internal exception wrapper classes in either production code or assertions.
- We are not altering the success-path JSON return behavior for deterministic skills.

## Implementation Approach

Add a small Bifrost strategy interface, `BifrostExceptionTransformer`, with a default implementation that unwraps to the deepest relevant cause and formats that throwable into the required AI-readable string. Inject that strategy into `SkillMethodBeanPostProcessor`, log the full top-level exception at the invocation boundary, and convert execution-time failures into the sanitized return string instead of rethrowing them. Keep serialization failures explicit `IllegalStateException`s because they are framework misuse/configuration problems rather than downstream tool-execution failures the LLM can recover from.

## Phase 1: Introduce the Transformer Abstraction

### Overview

Create the Bifrost-owned exception transformation contract and wire a default implementation into auto-configuration so the execution boundary can depend on it without hard-coding formatting logic.

### Changes Required:

#### 1. Core exception transformation types
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostExceptionTransformer.java`
**Changes**: Add a strategy interface that transforms a `Throwable` into a tool-safe string payload.

```java
public interface BifrostExceptionTransformer {

    String transform(Throwable throwable);
}
```

#### 2. Default formatter implementation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/DefaultBifrostExceptionTransformer.java`
**Changes**: Implement the ticket format by unwrapping nested causes first, then formatting the resolved throwable's simple class name and message with a safe fallback when the message is null or blank.

```java
public final class DefaultBifrostExceptionTransformer implements BifrostExceptionTransformer {

    @Override
    public String transform(Throwable throwable) {
        Throwable relevant = rootCauseOf(throwable);
        String hint = (relevant.getMessage() == null || relevant.getMessage().isBlank())
                ? "No additional details provided."
                : relevant.getMessage();
        return "ERROR: " + relevant.getClass().getSimpleName() + ". HINT: " + hint;
    }
}
```

#### 3. Auto-configuration wiring
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
**Changes**: Register the default transformer as an infrastructure bean and inject it into the `SkillMethodBeanPostProcessor` factory method so advanced users can override it with `@ConditionalOnMissingBean`.

```java
@Bean
@ConditionalOnMissingBean
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public BifrostExceptionTransformer bifrostExceptionTransformer() {
    return new DefaultBifrostExceptionTransformer();
}

@Bean
@ConditionalOnMissingBean
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public static SkillMethodBeanPostProcessor skillMethodBeanPostProcessor(
        CapabilityRegistry capabilityRegistry,
        BifrostExceptionTransformer bifrostExceptionTransformer) {
    return new SkillMethodBeanPostProcessor(
            capabilityRegistry,
            new ObjectMapper(),
            bifrostExceptionTransformer);
}
```

### Success Criteria:

#### Automated Verification:
- [x] Starter module compiles with the new strategy types: `.\mvnw.cmd -pl bifrost-spring-boot-starter -DskipTests compile`
- [x] Auto-configuration tests still pass after bean wiring changes: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`

#### Manual Verification:
-[x] The new abstraction is clearly Bifrost-owned and does not mention Spring AI exception internals.
-[x] The default transformer unwraps nested causes so business exception names survive framework wrappers in the returned payload.
-[x] The bean wiring still allows application-level overrides via standard Spring replacement.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual review was successful before proceeding to the next phase.

---

## Phase 2: Transform and Log Failures at the Shared Invocation Boundary

### Overview

Update the deterministic execution boundary so runtime tool failures are logged and returned as transformed strings while preserving existing success behavior.

### Changes Required:

#### 1. `SkillMethodBeanPostProcessor` exception handling
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`
**Changes**: Add an injected transformer field, a class logger, and a focused catch block for execution-time failures that logs the full stack trace and returns the transformed string. Keep the JSON serialization failure path as an `IllegalStateException`.

```java
private static final Logger log = LoggerFactory.getLogger(SkillMethodBeanPostProcessor.class);

private final BifrostExceptionTransformer bifrostExceptionTransformer;

private Object invokeToolCallback(MethodToolCallback toolCallback, String capabilityName, Map<String, Object> arguments) {
    Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
    try {
        String requestPayload = objectMapper.writeValueAsString(safeArguments);
        return toolCallback.call(requestPayload);
    }
    catch (JsonProcessingException ex) {
        throw new IllegalStateException("Failed to serialize capability arguments for " + capabilityName, ex);
    }
    catch (RuntimeException ex) {
        log.warn("Capability '{}' failed during deterministic execution", capabilityName, ex);
        return bifrostExceptionTransformer.transform(ex);
    }
}
```

#### 2. Constructor updates for a single implementation path
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`
**Changes**: Make one package-visible constructor the primary implementation path by accepting `CapabilityRegistry`, `ObjectMapper`, and `BifrostExceptionTransformer`. Keep the public convenience constructor as a thin delegate only, and have auto-configuration call the primary constructor so Spring and unit tests exercise the same logic path.

```java
public SkillMethodBeanPostProcessor(CapabilityRegistry capabilityRegistry) {
    this(capabilityRegistry, new ObjectMapper(), new DefaultBifrostExceptionTransformer());
}

SkillMethodBeanPostProcessor(CapabilityRegistry capabilityRegistry,
        ObjectMapper objectMapper,
        BifrostExceptionTransformer bifrostExceptionTransformer) {
    // single real constructor path
}
```

### Success Criteria:

#### Automated Verification:
- [x] The direct invocation unit tests pass with the new constructor and exception boundary: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SkillMethodBeanPostProcessorTest test`
- [x] The starter module test suite still passes after the runtime behavior change: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`

#### Manual Verification:
-[x] Successful deterministic invocations still return Spring AI's existing JSON-encoded success payloads.
-[x] Runtime tool failures produce an AI-readable error string with no stack trace text in the returned payload.
-[x] Wrapped framework exceptions still render the underlying business exception class/message in the payload.
-[x] System logs preserve the original top-level exception and stack trace for operators.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Add Direct and YAML Failure Coverage

### Overview

Extend the tests so the new boundary behavior is locked in for both direct discovered capabilities and YAML deterministic capabilities that reuse `target.invoker()`.

### Changes Required:

#### 1. Direct `@SkillMethod` failure tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
**Changes**: Add a fixture bean whose annotated method throws, assert that the invoker returns the transformed string, and capture logging output to verify the stack trace is logged while omitted from the payload. Include at least one wrapped-exception scenario so the formatted payload is asserted against the unwrapped business cause rather than the outer wrapper.

```java
@ExtendWith(OutputCaptureExtension.class)
class SkillMethodBeanPostProcessorTest {

    @Test
    void returnsTransformedErrorWhenSkillMethodThrows(CapturedOutput output) {
        // register throwing bean
        // invoke metadata.invoker().invoke(...)
        // assert returned payload matches transformer format
        // assert output contains exception type and stack trace text
        // assert returned payload does not contain stack trace frames
    }
}
```

#### 2. YAML-mapped failure propagation tests
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
**Changes**: Add an `ApplicationContextRunner` scenario using the existing mapped deterministic YAML fixture and a throwing target bean, then assert the YAML capability returns the same transformed string through the reused invoker path.

```java
@Test
void mappedDeterministicYamlSkillReturnsTransformedErrorWhenTargetThrows() {
    contextRunner
            .withPropertyValues("bifrost.skills.locations=classpath:/skills/valid/mapped-method-skill.yaml")
            .withUserConfiguration(ThrowingTargetBeanConfiguration.class)
            .run(context -> {
                CapabilityMetadata metadata = context.getBean(CapabilityRegistry.class)
                        .getCapability("mapped.method.skill");
                assertThat(metadata.invoker().invoke(Map.of("input", "alpha")))
                        .isEqualTo("ERROR: IllegalArgumentException. HINT: boom");
            });
}
```

#### 3. Optional bean-wiring assertion
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
**Changes**: Add a small assertion that the context exposes a `BifrostExceptionTransformer` bean so the new infrastructure dependency is covered by auto-configuration tests.

### Success Criteria:

#### Automated Verification:
- [x] Direct failure-path tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SkillMethodBeanPostProcessorTest test`
- [x] YAML mapped failure-path tests pass: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- [x] Auto-configuration coverage still passes with the new bean: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`

#### Manual Verification:
-[x] The direct and YAML tests demonstrate the same user-visible failure payload shape.
-[x] The captured log output shows operator-facing exception detail without leaking that detail into the tool result.
-[x] No existing deterministic success-path assertions needed to change except where constructor wiring was updated.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:
- Verify the default transformer unwraps nested causes before formatting exception class and message, including blank-message fallback behavior.
- Verify direct `@SkillMethod` invocation still succeeds unchanged for normal returns.
- Verify a throwing `@SkillMethod` returns the transformed string instead of propagating an `IllegalStateException`.
- Verify the returned error string omits stack-trace content while logs include it.

### Integration Tests:
- Verify a YAML deterministic skill using `mapping.target_id` returns the transformed error string when the underlying discovered target throws.
- Verify auto-configuration publishes the default transformer bean and still wires the skill method post-processor successfully.

**Note**: Prefer creating a dedicated testing plan artifact via `/testing_plan` before implementation so the failing-test-first sequence and exact command order are captured separately.

### Manual Testing Steps:
1. Run the starter module tests and confirm all existing deterministic happy-path tests still pass.
2. Trigger the new direct failure-path test and inspect the captured log output for a full exception stack trace.
3. Trigger the YAML mapped failure-path test and confirm the returned payload is the same sanitized string shape seen in the direct path.
4. Review the production code to confirm only execution-time failures are transformed, while argument serialization problems still fail fast.

## Performance Considerations

The feature should have negligible runtime cost because it only adds string formatting and logging on exceptional paths. Success-path performance remains unchanged aside from one extra injected dependency reference.

## Migration Notes

This is a behavior change rather than a schema or data migration. Existing callers that previously observed a thrown `IllegalStateException` from deterministic execution will now receive a string tool result for runtime method failures, and that payload will prefer the unwrapped business exception details over framework wrapper names, so release notes should call out the new contract for error handling.

## References

- Original ticket/requirements: `ai/thoughts/tickets/eng-006-exception-transformer.md`
- Related research: `ai/thoughts/research/2026-03-15-ENG-006-exception-transformer.md`
- Shared invocation boundary: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:49-80`
- YAML invoker reuse path: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java:39-48`
- Auto-configuration hook: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:39-44`
- Existing direct invocation tests: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:36-70`
- Existing YAML deterministic test: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java:106-120`
