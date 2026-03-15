# SkillMethod BeanPostProcessor Implementation Plan

## Overview

This plan details the implementation of the `SkillMethodBeanPostProcessor` to bridge annotated `@SkillMethod` beans into dynamically invocable Spring AI tool mappings. It abstracts arbitrary Java methods into Spring AI tool representations via reflection, enabling the LLM to call these methods natively using tool definition envelopes.

## Current State Analysis

- The annotations (`@SkillMethod`) and foundational interfaces (`CapabilityRegistry`, `CapabilityMetadata`, `CapabilityInvoker`) exist within `com.lokiscale.bifrost.core` and `com.lokiscale.bifrost.annotation`.
- The parent POM manages the `spring-ai-bom`, but the `bifrost-spring-boot-starter` lacks the explicit dependency on Spring AI.
- There is currently no `BeanPostProcessor` intercepting the `@SkillMethod` lifecycle during Spring application startup, nor is there a mapper generating JSON schemas or function wrappers.

## Desired End State

After this plan is complete:
1. `bifrost-spring-boot-starter` will depend on `spring-ai-model` to utilize its native method mapping utilities.
2. `SkillMethodBeanPostProcessor` will scan the application context for beans with `@SkillMethod` annotated methods.
3. For each matching method, it will construct a `CapabilityMetadata` wrapper encapsulating a `CapabilityInvoker`.
4. The generated `CapabilityInvoker` will utilize Spring AI's native functionality (specifically `MethodToolCallback` or underlying binding abstractions) to execute LLM JSON envelopes dynamically as method arguments, handling argument binding automatically via Spring AI.
5. All discovered and wrapped methods will be registered with the `CapabilityRegistry`.
6. Unit tests will enforce expected discovery, registration, and reflection-based invocation success.

### Key Discoveries:
- Spring AI’s parameter binding handles mapping a flat JSON model (where each argument is a top-level property) directly into method parameters. By leaning on `MethodToolCallback`, we gain automatic support for optionality, complex type conversion, and `@ToolParam` descriptions.
- `CapabilityInvoker` defines an interface `Object invoke(Map<String, Object> arguments);` – we can serialize the incoming `Map` into a JSON string via Jackson internally and proxy it to Spring AI's callbacks, preserving the generic `Map` context upstream.

## What We're NOT Doing

- We are NOT implementing the actual logic that registers these tools with multiple LLM clients yet (this focuses strictly on gathering and storing them in the registry as executable capabilities).
- We are NOT building custom reflection-based ObjectMapper logic from scratch, opting instead to leverage Spring AI’s built-in parameter binding layer.
- We are NOT modifying the existing `InMemoryCapabilityRegistry` interface or its behaviors.

## Implementation Approach

1. **Dependency Management**: Add `spring-ai-model` dependency to `bifrost-spring-boot-starter`.
2. **Post-Processor Skeleton**: Create the `SkillMethodBeanPostProcessor` implementing Spring's `BeanPostProcessor`.
3. **Reflection & Binding Integration**: Program the scanning component to iterate over methods, construct Spring AI's `MethodToolCallback` configurations to generate JSON Schemas, and build the wrapped `CapabilityInvoker` using a Map-to-JSON serialization strategy bridged to Spring AI calls.
4. **Registration**: Hook into the `CapabilityRegistry` during the post-processor phase.
5. **Testing**: Write comprehensive unit tests guaranteeing successful interception and invocation.

---

## Phase 1: Core Dependencies and Processor Skeleton

### Overview
Bring in necessary dependencies and ensure `SkillMethodBeanPostProcessor` correctly discovers and logs target methods during context startup.

### Changes Required:

#### 1. Spring Boot Starter Dependencies
**File**: `bifrost-spring-boot-starter/pom.xml`
**Changes**: Add `spring-ai-model` dependency. Provide `jackson-databind` as well if absent.

```xml
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-model</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
```

#### 2. Create Post-Processor Skeleton
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`
**Changes**: Implement `BeanPostProcessor`.

```java
package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.annotation.SkillMethod;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Component
public class SkillMethodBeanPostProcessor implements BeanPostProcessor {

    private final CapabilityRegistry capabilityRegistry;

    public SkillMethodBeanPostProcessor(CapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        ReflectionUtils.doWithMethods(bean.getClass(), method -> {
            SkillMethod annotation = method.getAnnotation(SkillMethod.class);
            if (annotation != null) {
                // To be implemented in Phase 2
            }
        });
        return bean;
    }
}
```

### Success Criteria:

#### Automated Verification:
- [x] Maven builds successfully: `mvn clean compile -pl bifrost-spring-boot-starter`

#### Manual Verification:
- [ ] Dependency tree resolves without conflicts.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Tool Wrapper and Registration

### Overview
Inject the true mapping behavior. Generate `MethodToolCallback` from Spring AI for each `@SkillMethod` to extract schemas, and store a lambda as `CapabilityInvoker` that delegates incoming execution.

### Changes Required:

#### 1. Implement Processor Registration Logic
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`
**Changes**: Wire the bean method through Spring AI's builder, mapping the wrapper as a Capability.

```java
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.Set;

// Within postProcessAfterInitialization method
ReflectionUtils.doWithMethods(bean.getClass(), method -> {
    SkillMethod annotation = method.getAnnotation(SkillMethod.class);
    if (annotation != null) {
        String name = annotation.name().isBlank() ? method.getName() : annotation.name();
        
        MethodToolCallback toolCallback = MethodToolCallback.builder()
            .toolMethod(method)
            .toolObject(bean)
            .toolDefinition(ToolDefinition.builder(method)
                .name(name)
                .description(annotation.description())
                .build())
            .build();
            
        ObjectMapper objectMapper = new ObjectMapper();
        
        CapabilityInvoker invoker = arguments -> {
            try {
                // Envelope translation: The caller sends Map<String,Object>
                // stringify to hand to Spring AI's binding logic.
                String requestPayload = objectMapper.writeValueAsString(arguments);
                return toolCallback.call(requestPayload);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke capability via tool callback", e);
            }
        };

        CapabilityMetadata metadata = new CapabilityMetadata(
                UUID.randomUUID().toString(),
                name,
                annotation.description(),
                annotation.modelPreference(),
                Set.of(),
                invoker
        );

        capabilityRegistry.register(name, metadata);
    }
});
```

### Success Criteria:

#### Automated Verification:
- [x] Clean compilation.

#### Manual Verification:
- [ ] The generated metadata integrates Spring AI's native framework dynamically.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Writing the Test Harness

### Overview
Draft unit testing configurations to mock LLM envelope behavior.

### Changes Required:

#### 1. Unit Test 
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
**Changes**: Add testing contexts ensuring discovery and invocation operate optimally.

### Success Criteria:

#### Automated Verification:
- [x] `SkillMethodBeanPostProcessorTest` passes perfectly via `mvn test`.
- [x] Entire project builds via `mvn clean verify`.

#### Manual Verification:
- [ ] Review implementation syntax carefully. No explicit test gaps detected.

---

## Testing Strategy

### Unit Tests:
- Scanning accuracy (methods with vs without annotation).
- Parameter resolution testing matching Envelope objects to arbitrary args (`@ToolParam`, Optionals).
- Extraneous parameter handling mapped against Spring AI schema outputs.

### Integration Tests:
- Ensure bean lifecycles inside the explicit `@SpringBootTest` context resolve perfectly with `CapabilityRegistry`.
- Verify runtime context doesn't crash on unresolved beans.

## Performance Considerations

Using `ObjectMapper` per execution carries slight overhead but it aligns perfectly with Spring AI's standard framework envelope processing.

## Migration Notes
- No backward compatibility considerations needed as phase 2 builds on nascent foundations.

## References
- Original ticket/requirements: `ai/thoughts/tickets/eng-002-skillmethod-bpp.md`
- Context: `ai/thoughts/tickets/eng-002-skillmethod-bpp.md`
- Related research: `ai/thoughts/research/2026-03-14-ENG-002-skillmethod-bpp.md`
