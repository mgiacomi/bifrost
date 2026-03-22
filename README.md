# Bifrost

Bifrost is an AI skill orchestration framework that makes it simple to integrate LLM capabilities natively into Java applications. It allows developers to define AI skills either through declarative YAML manifests or by binding directly to traditional Java methods using the `@SkillMethod` annotation.

## Project Structure

Bifrost consists of several modules:
- **`bifrost-core`**: The foundational abstractions, models, and core logic of the framework.
- **`bifrost-spring-boot-starter`**: The auto-configuration module that seamlessly integrates Bifrost into Spring Boot applications, handling skill discovery, registration, and dependency injection.
- **`bifrost-sample`**: A sample application demonstrating how to consume the starter, configure the framework, and implement skills.

## Getting Started

To use Bifrost in your project, add the starter dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.lokiscale.bifrost</groupId>
    <artifactId>bifrost-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version> <!-- Update to the current release version -->
</dependency>
```

### Configuration

Bifrost can be configured using standard Spring Boot properties (e.g., `application.yml`). You'll need to specify where the framework should look for declarative YAML skills and how to connect to the underlying LLM models.

```yaml
bifrost:
  skills:
    locations:
      - classpath:/skills/**/*.yml
      - classpath:/skills/**/*.yaml
  models:
    default-model:
      provider: ollama
      provider-model: ibm/granite4:tiny-h
```

## Defining Skills

Bifrost supports two primary ways to define skills: Declarative YAML and Java Annotations.

### 1. Declarative YAML Skills

Ideal for purely declarative prompts or specific LLM extraction/transformation tasks. Place these files in your configured skills location (e.g., `src/main/resources/skills/`).

```yaml
name: invoiceParser
description: Parses an unstructured invoice payload extracting Date, Amount, and Vendor.
model: default-model
```

### 2. Java `@SkillMethod` Annotation

Ideal when you need to execute programmatic logic, interact with databases, or call external APIs as part of the skill's execution.

```java
import com.lokiscale.bifrost.annotation.SkillMethod;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ExpenseService {

    @SkillMethod(name = "getLatestExpenses", description = "Returns a fake list of recent expenses.")
    public List<Map<String, Object>> getLatestExpenses() {
        return List.of(
            Map.of("category", "Software", "amount", 120.00, "date", "2026-03-20")
        );
    }
}
```

## Running the Sample Application

The `bifrost-sample` module provides a working example of the framework.

1. Navigate to the sample directory: `cd bifrost-sample`
2. Run the application: `mvn spring-boot:run`

This will bootstrap the application and register both the YAML skills and the annotated methods, making them available to the Bifrost orchestrator.

## Limitations

- The framework is currently under active development.
- The sample application currently mocks data and does not actively execute against a live LLM endpoint by default.
