# Bifrost

A Java Spring Boot–based, agentic AI framework that uses LLM‑driven skills within a Hierarchical Task Network (HTN) architecture.

Bifrost while still an HTN is fundamentally different from traditional HTNs. Instead of relying on rigid, rule‑based planners, Bifrost blends classical HTN structure with LLM‑powered reasoning, allowing agents to dynamically decompose missions, select skills, and orchestrate complex workflows. 

At its core, Bifrost treats skills as the fundamental building blocks of capability. YAML skills are model-driven and can call other visible capabilities; Java `@SkillMethod`s provide deterministic application logic. This creates a flexible planning system that combines LLM reasoning with explicit contracts and ordinary Spring services.


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


## Requirements

- Java 21 or newer
- Maven 3.9 or newer (the included Maven wrapper is recommended)
- A configured Spring AI chat-model provider, such as Ollama, OpenAI, Anthropic, or Google GenAI

## Project Structure

Bifrost currently contains three projects:

- `bifrost-spring-boot-starter`: the core starter.
- `bifrost-sample`: a sample Spring Boot application.
- `bifrost-cli`: a separate Go command-line project for inspecting and testing Bifrost traces.

## Getting Started

Add the starter to your application:

```xml
<dependency>
    <groupId>com.lokiscale.bifrost</groupId>
    <artifactId>bifrost-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Configure a Spring AI provider, skill locations, and one or more named Bifrost models in `application.yml`:

```yaml
spring:
  application:
    name: bifrost-sample
  ai:
    ollama:
      enabled: true
      base-url: http://localhost:11434
      chat:
        options:
          model: ibm/granite4:tiny-h
          temperature: 0.7

server:
  port: 8081

logging:
  level:
    com.lokiscale.bifrost.sample: INFO

bifrost:
  session:
    mission-timeout: 6000s
  skills:
    locations:
      - classpath:/skills/**/*.yml
      - classpath:/skills/**/*.yaml
  models:
    granite4-tiny:
      provider: ollama
      provider-model: ibm/granite4:tiny-h
    default-model:
      provider: ollama
      provider-model: ibm/granite4:tiny-h

execution-trace:
  persistence: ALWAYS
```

Every LLM-backed YAML skill must name one of the entries under `bifrost.models`. Mapped Java skills do not declare a model. `default-model` is an ordinary model key; it is not selected automatically.

By default, Bifrost discovers `classpath:/skills/**/*.yaml`. Add the `.yml` pattern, as above, when your application uses that extension.

### Invoking a skill

Inject `SkillTemplate` and invoke a YAML skill with a map (or an object that can be converted to a map). The result is returned as text; use an `output_schema` when the caller needs a predictable JSON shape.

```java
import com.lokiscale.bifrost.skillapi.SkillTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class InvoiceWorkflow {
    private final SkillTemplate skills;

    public InvoiceWorkflow(SkillTemplate skills) {
        this.skills = skills;
    }

    public String checkInvoice(String invoiceText) {
        return skills.invoke("duplicateInvoiceChecker", Map.of("payload", invoiceText));
    }
}
```

## Defining Skills

### YAML skills

An LLM-backed YAML skill omits `mapping`, declares a configured `model`, and may use model execution settings. `prompt` supplies private instructions in addition to the public `description`.

```yaml
name: duplicateInvoiceChecker
description: >
  Checks whether a given invoice already exists in the expense system.
  First, parses the raw invoice text to extract vendor, amount, and date.
  Then, retrieves existing expenses and compares them to determine
  if the invoice is a duplicate.
model: granite4-tiny
planning_mode: true
max_steps: 10
allowed_skills: [invoiceParser, expenseLookup]
evidence_contract:
  claims:
    vendorName: [parsed_invoice]
    invoiceDate: [parsed_invoice]
    totalAmount: [parsed_invoice]
    isDuplicate: [parsed_invoice, expense_match_search]
    reasoning: [parsed_invoice, expense_match_search]
  tool_evidence:
    invoiceParser: [parsed_invoice]
    expenseLookup: [expense_match_search]
output_schema:
  type: object
  properties:
    isDuplicate:
      type: boolean
      description: True if a matching expense was found in the system
    vendorName:
      type: string
      description: Vendor name extracted from the invoice
    totalAmount:
      type: number
      description: Total amount extracted from the invoice
    invoiceDate:
      type: string
      description: Invoice date in ISO-8601 format (YYYY-MM-DD)
    reasoning:
      type: string
      description: Brief explanation of why the invoice was or was not considered a duplicate
  required: [isDuplicate, vendorName, totalAmount, invoiceDate, reasoning]
  additionalProperties: false
output_schema_max_retries: 2
```

Important execution settings:

- `planning_mode`: enables the step-based HTN executor only when set to `true`. It is disabled by default.
- `allowed_skills`: limits the YAML skills visible to a planning skill. Use public YAML manifest names only; Java target IDs are internal mapping metadata.
- `max_steps`: bounds planning-loop steps.
- `prompt`: optional private instructions for an LLM-backed skill.
- `thinking_level`: selects a configured thinking level for models that support it.
- `input_schema`: validates and describes the expected input. Supported types are `object`, `array`, `string`, `number`, `integer`, `boolean`, and `attachment`.
- `output_schema`: validates the model response. When present, `output_schema_max_retries` defaults to `2` and accepts values from `0` through `3`.
- `linter`: currently supports a `regex` linter with `max_retries` from `0` through `3`.
- `evidence_contract`: declares evidence required to support output claims in a planning skill.
- `rbac_roles`: requires the current Spring Security authentication to have one of the listed roles before the skill is visible or executable.

For attachment inputs, declare `type: attachment`, a `media_type` (`image`, `pdf`, `audio`, `video`, or `file`), and permitted `allowed_content_types`. Pass a Spring `Resource` or a `ref://...` virtual-file reference as the input value.

### Mapping a YAML skill to Java

YAML manifest `name` is the only public Bifrost skill identity. Use `mapping.target_id` to connect that public YAML skill to an internal Java implementation target identified by `beanName#methodName`. A mapped wrapper may contain only `name`, `description`, optional `rbac_roles`, and a nonblank `mapping.target_id`.

Declaring `mapping`, even as `null` or an empty block, selects mapped validation and requires a nonblank target. Mapped input and output behavior is owned by the Java target: Bifrost publishes its reflected input contract, and a different public shape requires a separate Java adapter target. Model/runtime fields such as `model`, `prompt`, schemas, planning, tool allowlists, linting, retries, and evidence contracts are rejected on the mapped child. An LLM parent may still list the child in its own `allowed_skills` and `evidence_contract.tool_evidence`.

```yaml
name: expenseLookup
description: Retrieves the most recent expenses.
mapping:
  target_id: expenseService#getLatestExpenses
```

### Java `@SkillMethod` implementation targets

Use `@SkillMethod` when the implementation should run deterministic Java logic. It registers an internal target, not a public capability or alias. Expose it through a mapped YAML manifest before application code can invoke it or another YAML skill can list it in `allowed_skills`; both surfaces accept YAML names only.

```java
import com.lokiscale.bifrost.annotation.SkillMethod;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ExpenseService {

    @SkillMethod(description = "Returns a fake list of recent expenses.")
    public List<Map<String, Object>> getLatestExpenses() {
        return List.of(
            Map.of("category", "Software", "amount", 120.00, "date", "2026-03-20")
        );
    }
}
```

## Operations and limits

`bifrost.session` provides execution safeguards. Defaults are a 60-second mission timeout, maximum depth 32, 64 skill invocations, 128 tool invocations, 32 linter retries, 64 model calls, and 200,000 usage units. Attachments default to a 20 MB maximum size.

```yaml
bifrost:
  session:
    mission-timeout: 60s
    max-depth: 32
    attachments:
      max-size: 20MB
    quotas:
      max-skill-invocations: 64
      max-tool-invocations: 128
      max-linter-retries: 32
      max-model-calls: 64
      max-usage-units: 200000

execution-trace:
  persistence: ONERROR # NEVER, ONERROR, or ALWAYS
```

When Micrometer is on the application classpath, Bifrost records usage metrics automatically. Execution traces and the `SkillTemplate` observer callback can be used to inspect a completed skill execution.

## Running The Sample

The OpenAI-backed feedstock examples read the API key from `OPENAI_API_KEY`; keep the value in your environment rather than committing it to configuration.

On Windows PowerShell:

```powershell
$env:OPENAI_API_KEY = "sk-..."
```

To persist it for your Windows user account:

```powershell
setx OPENAI_API_KEY "sk-..."
```

From the repository root:

```bash
./mvnw -pl bifrost-sample spring-boot:run
```

On Windows PowerShell:

```powershell
.\mvnw.cmd -pl bifrost-sample spring-boot:run
```

The sample app loads skills from `classpath:/skills/**/*.yml` and `classpath:/skills/**/*.yaml` and configures named Ollama and OpenAI models in [application.yml](/C:/opendev/code/bifrost/bifrost-sample/src/main/resources/application.yml).
