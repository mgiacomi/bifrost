# Bifrost

A Spring Boot–based, agentic AI framework that uses LLM‑driven skills within a Hierarchical Task Network (HTN) architecture.

Bifrost while still an HTN is fundamentally different from traditional HTNs. Instead of relying on rigid, rule‑based planners, Bifrost blends classical HTN structure with LLM‑powered reasoning, allowing agents to dynamically decompose missions, select skills, and orchestrate complex workflows. 

At its core, Bifrost treats skills as the fundamental building blocks of capability. Each skill is defined in natural language and executed by an LLM, which can decide—based on context, data, and the mission—whether to call other skills, chain them, or complete the task directly. This creates a flexible, adaptive planning system that feels closer to human reasoning than traditional symbolic planners.


## Why Bifrost?
Most HTN planners (like JSHOP2 or PANDA) rely on static, hand‑coded methods. They’re powerful, but brittle. Bifrost takes a different approach:
- LLM‑driven decomposition
The agent decides how to break down a mission in real time.
- Skill‑based execution
Each skill is a modular, reusable capability that can call others.
- Natural‑language domain modeling
No DSLs or planning languages — skills are written in plain English.
- Spring Boot foundation
Easy integration, dependency injection, configuration, and deployment.
The result is a hybrid system that combines the structure of HTNs with the adaptability of modern LLMs.


## Project Structure

Bifrost currently contains three modules:

- `bifrost-spring-boot-starter`: the core starter.
- `bifrost-sample`: a sample Spring Boot application.
- `bifrost-cli`: a command line tool to debug and test Bifrost.

## Getting Started

Add the starter to your application:

```xml
<dependency>
    <groupId>com.lokiscale.bifrost</groupId>
    <artifactId>bifrost-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Configure skill locations and at least one model in `application.yml`:

```yaml
bifrost:
  skills:
    locations:
      - classpath:/skills/**/*.yml
      - classpath:/skills/**/*.yaml
  models:
    default-model:
      provider: taalas
      provider-model: llama3.1-8B
```

## Defining Skills

### YAML skills

YAML skills define a skill name, description, and execution settings.

```yaml
name: invoiceParser
description: Parse an invoice payload.
model: default-model
```

### Java `@SkillMethod` skills

Use `@SkillMethod` when the implementation should run deterministic Java logic.

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

## Running The Sample

From the repository root:

```bash
./mvnw -pl bifrost-sample spring-boot:run
```

On Windows PowerShell:

```powershell
.\mvnw.cmd -pl bifrost-sample spring-boot:run
```

The sample app loads skills from `classpath:/skills/**/*.yml` and `classpath:/skills/**/*.yaml` and configures a default Taalas-backed model in [application.yml](/C:/opendev/code/bifrost/bifrost-sample/src/main/resources/application.yml).
