# Bifrost

Bifrost is a Spring Boot-first AI skill orchestration framework for Java applications. It supports YAML-defined skills and `@SkillMethod`-backed deterministic skills.

## Project Structure

Bifrost currently contains two modules:

- `bifrost-spring-boot-starter`: the core starter.
- `bifrost-sample`: a sample Spring Boot application.

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
