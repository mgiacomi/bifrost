# ENG-006 Exception Transformer Testing Plan

## Change Summary
- Add a Bifrost-owned `BifrostExceptionTransformer` strategy and default implementation for deterministic `@SkillMethod` failures.
- Change `SkillMethodBeanPostProcessor.invokeToolCallback(...)` so execution-time failures are logged and returned as AI-readable tool results instead of being rethrown as `IllegalStateException`.
- Preserve business exception names/messages in the returned payload by unwrapping nested causes before formatting.
- Ensure the same behavior applies to direct discovered capabilities and YAML deterministic skills that reuse `mapping.target_id`.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostExceptionTransformer.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/DefaultBifrostExceptionTransformer.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`

## Risk Assessment
- High-risk behavior: execution failures that currently throw will become string tool results, so tests need to prove the new contract and guard against accidental regressions back to exception propagation.
- High-risk behavior: framework wrapper exceptions may leak wrapper names unless the transformer consistently unwraps to the business cause first.
- High-risk behavior: logs must keep the full stack trace while the returned payload must omit it entirely.
- Edge case: blank or null exception messages need a stable fallback hint.
- Edge case: argument serialization failures should still fail fast rather than being transformed into tool results.
- Integration risk: YAML deterministic skills must inherit the same failure behavior through reused invokers without separate YAML-specific logic.

## Existing Test Coverage
- `SkillMethodBeanPostProcessorTest` already verifies registration, happy-path invocation, and optional-parameter handling for direct `@SkillMethod` execution.
- `YamlSkillCatalogTests` already verifies deterministic YAML skills resolve through `mapping.target_id` and invoke the discovered target successfully.
- `BifrostAutoConfigurationTests` already verifies starter beans and mapped YAML/discovered target registration coexist in the same context.
- Gap: there is no current failing-path test for a thrown `@SkillMethod`.
- Gap: there is no assertion that logs contain the real exception while the payload omits stack-trace text.
- Gap: there is no explicit coverage for wrapped exceptions being unwrapped to the business cause in the returned payload.
- Gap: there is no explicit auto-config assertion that a default `BifrostExceptionTransformer` bean is published.

## Bug Reproduction / Failing Test First
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- Arrange/Act/Assert outline:
  - Register a fixture bean with an annotated `@SkillMethod` that throws a wrapped exception such as `new IllegalStateException("wrapper", new IllegalArgumentException("boom"))`.
  - Look up the registered capability and invoke it with a minimal valid argument map.
  - Assert that the returned result is the string `ERROR: IllegalArgumentException. HINT: boom`.
  - Assert that captured logs contain the thrown exception/stack trace.
- Expected failure (pre-fix):
  - The test fails because the current implementation throws `IllegalStateException("Failed to invoke capability ...", ex)` instead of returning a transformed string.

## Tests to Add/Update
### 1) `returnsTransformedErrorWhenSkillMethodThrowsWrappedBusinessException`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- What it proves: direct `@SkillMethod` invocation catches an execution failure, unwraps to the business cause, returns the sanitized `ERROR: ... HINT: ...` string, and does not propagate a framework exception.
- Fixtures/data: a local fixture bean with an annotated method that throws a wrapped exception; a valid single-argument map.
- Mocks: none; use the real `SkillMethodBeanPostProcessor` and `InMemoryCapabilityRegistry`.

### 2) `logsStackTraceButOmitsItFromReturnedPayload`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- What it proves: the operator-facing logs contain the actual exception and stack trace while the tool result string omits stack-trace frames and wrapper-noise.
- Fixtures/data: `CapturedOutput` via `OutputCaptureExtension`; a throwing fixture bean whose exception message is distinctive.
- Mocks: none; rely on Spring Boot's log capture support from `spring-boot-starter-test`.

### 3) `defaultTransformerUsesRootCauseAndFallbackHint`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/DefaultBifrostExceptionTransformerTest.java`
- What it proves: the transformer unwraps nested causes to the deepest relevant business exception and formats a safe fallback message when the resolved cause has a null or blank message.
- Fixtures/data: one nested exception graph with a meaningful root cause and one throwable with a blank/null message.
- Mocks: none.

### 4) `mappedDeterministicYamlSkillReturnsTransformedErrorWhenTargetThrows`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves: YAML deterministic skills using `mapping.target_id` inherit the exact transformed failure behavior from the shared invoker path.
- Fixtures/data: existing `classpath:/skills/valid/mapped-method-skill.yaml`; a test configuration exposing a throwing target bean with the mapped capability id; a valid input map.
- Mocks: none; use `ApplicationContextRunner` and the real starter auto-configuration.

### 5) `publishesDefaultBifrostExceptionTransformerBean`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- What it proves: auto-configuration publishes the default transformer bean and the starter context still wires successfully with the new dependency.
- Fixtures/data: existing `ApplicationContextRunner` setup and a minimal skills location.
- Mocks: none.

### 6) Update happy-path assertions for constructor parity
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- What it proves: existing success-path tests still pass after the constructor refactor and behavior change, guarding against accidental changes to JSON-encoded success responses.
- Fixtures/data: existing registration and invocation fixtures.
- Mocks: none.

## How to Run
- Build the starter module: `.\mvnw.cmd -pl bifrost-spring-boot-starter -DskipTests compile`
- Run the direct deterministic invocation tests: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=SkillMethodBeanPostProcessorTest test`
- Run the transformer-focused unit tests: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=DefaultBifrostExceptionTransformerTest test`
- Run the YAML deterministic integration tests: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests test`
- Run the auto-configuration integration tests: `.\mvnw.cmd -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`
- Run the full starter module suite: `.\mvnw.cmd -pl bifrost-spring-boot-starter test`

## Exit Criteria
- [x] A minimal failing unit test exists and fails pre-fix because deterministic execution still throws instead of returning a transformed string.
- [x] Post-fix direct invocation tests prove wrapped exceptions are unwrapped to the business cause in the returned payload.
- [x] Post-fix tests prove logs retain the full exception/stack trace while the returned payload omits it.
- [x] Post-fix transformer unit tests cover both normal root-cause formatting and blank/null-message fallback behavior.
- [x] Post-fix YAML integration tests prove `mapping.target_id` skills return the same transformed failure payload as direct invocation.
- [x] Post-fix auto-configuration tests prove the default transformer bean is published and the starter context still boots cleanly.
- [x] Existing happy-path deterministic tests continue to pass unchanged in user-visible behavior.
- [ ] Manual verification confirms argument serialization failures still fail fast instead of being converted into tool results.
