# Bifrost

A Java Spring Boot–based, agentic AI framework that uses LLM‑driven skills within a Hierarchical Task Network (HTN) architecture.

Bifrost while still an HTN is fundamentally different from traditional HTNs. Instead of relying on rigid, rule‑based planners, Bifrost blends classical HTN structure with LLM‑powered reasoning, allowing agents to dynamically decompose missions, select skills, and orchestrate complex workflows. 

At its core, Bifrost treats skills as the fundamental building blocks of capability. YAML manifests define every public skill: an LLM-backed skill can reason and call other visible YAML skills, while a mapped YAML skill exposes deterministic application logic implemented by a Java `@SkillMethod`. This creates a flexible planning system that combines LLM reasoning with explicit contracts and ordinary Spring services.


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
- At least one named Bifrost AI connection using the Ollama, OpenAI, Anthropic, or Gemini driver

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

Configure application-owned AI connections, skill locations, and named Bifrost model aliases in `application.yml`:

```yaml
server:
  port: 8081

logging:
  level:
    com.lokiscale.bifrost.sample: INFO

bifrost:
  connections:
    ollama-main:
      driver: ollama
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
    openai-main:
      driver: openai
      api-key: ${OPENAI_API_KEY}
  session:
    mission-timeout: 6000s
  skills:
    locations:
      - classpath:/skills/**/*.yml
      - classpath:/skills/**/*.yaml
  models:
    granite4-tiny:
      connection: ollama-main
      provider-model: ibm/granite4:tiny-h
    default-model:
      connection: ollama-main
      provider-model: ibm/granite4:tiny-h

execution-trace:
  persistence: ALWAYS
```

Every LLM-backed YAML skill must name one of the entries under `bifrost.models`. Mapped YAML skills do not declare a model. `default-model` is an ordinary model key; it is not selected automatically.

A connection is a concrete endpoint/account and chooses a built-in `driver`; a model is a framework alias that chooses a connection and the request-level `provider-model`. Multiple connections may use the same driver. Bifrost does not merge or inherit `spring.ai.*` settings. Keep credentials in environment variables or an external secret store.

The `openai` driver uses the OpenAI chat-completions protocol and supports custom `base-url`, static `headers`, organization/project IDs, and a custom chat-completions path. Use it only for compatible services. A `base-url` that already ends in `/v1` is combined with `/chat/completions`; an unversioned base URL uses `/v1/chat/completions`. Set `openai.chat-completions-path` for a different route. The `ollama` driver uses Ollama's native `/api/chat` protocol. Anthropic supports its native base URL and version/path options. Gemini supports either API-key mode or Vertex AI mode (`project-id` and `location`, with optional credentials resource), but not both on one connection.

Several model aliases can share one connection while choosing different provider model IDs. An OpenAI-compatible gateway is another named connection using `driver: openai`; it does not need a vendor-specific driver:

```yaml
bifrost:
  connections:
    openrouter:
      driver: openai
      base-url: https://openrouter.ai/api/v1
      api-key: ${OPENROUTER_API_KEY}
      headers:
        HTTP-Referer: ${OPENROUTER_SITE_URL}
  models:
    fast:
      connection: openai-main
      provider-model: gpt-4o-mini
    deep:
      connection: openai-main
      provider-model: gpt-5
    routed-sonnet:
      connection: openrouter
      provider-model: anthropic/claude-sonnet-4
```

Endpoint compatibility is feature-specific: verify tools, media, structured output, reasoning fields, and usage reporting against the selected service.

By default, Bifrost discovers `classpath:/skills/**/*.yaml`. Add the `.yml` pattern, as above, when your application uses that extension.

### Invoking a skill

Inject `SkillTemplate` and invoke a YAML skill with a map (or an object that can be converted to a map). The result is returned as text; use an `output_schema` when the caller needs a predictable JSON shape.

```java
import com.lokiscale.bifrost.api.SkillTemplate;
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

The supported starter API is closed to these seven types in `com.lokiscale.bifrost.api`: `SkillTemplate`, `SkillExecutionView`, `SkillExecutionEvent`, `SkillMethod`, `SkillException`, `SkillInputValidationException`, and `SkillInputValidationIssue`. `SkillTemplate` is injectable and easy to mock in application tests, but replacing its framework bean or implementing Bifrost internals is unsupported. There are currently no supported Bifrost-specific SPIs or bean overrides; other starter types are internal before 1.0.

For integration testing, configure a real or local protocol-compatible named connection and invoke the YAML skill through `SkillTemplate`. Bifrost's supported-surface integration test follows this pattern: it supplies a local OpenAI-compatible endpoint through `bifrost.connections`, invokes an LLM-backed YAML skill through the public facade, and observes only `SkillExecutionView` values. Tests should not replace internal resolvers, coordinators, chat-client factories, registries, or virtual-file-system beans.

Successful observers receive a session ID and immutable, current-version `SkillExecutionEvent` values. These events are intended for trusted development and debugging, may contain application business data, and are not a durable or comprehensively sanitized trace contract. Invalid caller input raises `SkillInputValidationException`, authorization failures remain Spring Security `AccessDeniedException`, and other runtime failures crossing the facade become a safe `SkillException`.

## Defining Skills

### YAML skills

An LLM-backed YAML skill omits `mapping`, declares a configured `model`, and may use model execution settings. `prompt` supplies private instructions in addition to the public `description`.

The YAML `name` is the skill's single public identity and must match `^[A-Za-z_][A-Za-z0-9_]{0,63}$`: use 1-64 characters, start with an ASCII letter or underscore, and then use only ASCII letters, digits, or underscores. Names are case-sensitive and Bifrost does not trim, sanitize, normalize, truncate, or alias them. Descriptive lowerCamelCase names such as `duplicateInvoiceChecker` and `expenseLookup` are the recommended authoring style, though underscores and uppercase starts are also valid. Use the exact YAML name in `SkillTemplate`, `allowed_skills`, and property-level `evidence` expressions.

This public-name rule does not apply to `mapping.target_id`. That field is internal mapping metadata and intentionally uses separate `beanName#methodName` syntax.

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
output_schema:
  type: object
  properties:
    isDuplicate:
      type: boolean
      evidence: invoiceParser and expenseLookup
      description: True if a matching expense was found in the system
    vendorName:
      type: string
      evidence: invoiceParser
      description: Vendor name extracted from the invoice
    totalAmount:
      type: number
      evidence: invoiceParser
      description: Total amount extracted from the invoice
    invoiceDate:
      type: string
      evidence: invoiceParser
      description: Invoice date in ISO-8601 format (YYYY-MM-DD)
    reasoning:
      type: string
      evidence: invoiceParser and expenseLookup
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
- `output_schema.properties.<name>.evidence`: attaches a nonblank Boolean expression over exact direct `allowed_skills` names to an immediate root output property. Operators `and` and `or` are case-insensitive; skill names remain case-sensitive; `and` binds more tightly than `or`, and parentheses override precedence. Plan validation checks every annotated property against planned child names; final validation checks only annotated properties present in the candidate against successfully completed direct children. Nested child internals do not leak upward. The annotation is orchestration metadata, not candidate JSON, and enforces supportability rather than factual truth or workflow order. Nested-schema annotations are unsupported.
- `rbac_roles`: requires the current Spring Security authentication to have one of the listed roles before the skill is visible or executable.

For attachment inputs, declare `type: attachment`, a `media_type` (`image`, `pdf`, `audio`, `video`, or `file`), and permitted `allowed_content_types`. Pass a Spring `Resource` or a `ref://...` virtual-file reference as the input value.

### Mapping a YAML skill to Java

YAML manifest `name` is the only public Bifrost skill identity. Use `mapping.target_id` to connect that public YAML skill to an internal Java implementation target identified by `beanName#methodName`. A mapped wrapper must declare `name`, `description`, and a nonblank `mapping.target_id`; its only optional field is `rbac_roles`.

Declaring `mapping`, even as `null` or an empty block, selects mapped validation and requires a nonblank target. Mapped input and output behavior is owned by the Java target: Bifrost publishes its reflected input contract, and a different public shape requires a separate Java adapter target. Model/runtime fields such as `model`, `prompt`, schemas, planning, tool allowlists, linting, retries, and evidence annotations are rejected on the mapped child. An LLM parent may still list the child in its own `allowed_skills` and property-level `evidence` expressions.

The public YAML name may equal the Java method name because public skills and implementation targets use separate namespaces. Multiple mapped YAML skills may also share one Java target. Within a single Spring bean, however, annotated method names must be unique: overloaded `@SkillMethod`s would produce the same `beanName#methodName` target ID and fail startup.

```yaml
name: expenseLookup
description: Retrieves the most recent expenses.
mapping:
  target_id: expenseService#getLatestExpenses
```

### Java `@SkillMethod` implementation targets

Use `@SkillMethod` when the implementation should run deterministic Java logic. It registers an internal target, not a public capability or alias. Expose it through a mapped YAML manifest before application code can invoke it or another YAML skill can list it in `allowed_skills`; both surfaces accept YAML names only.

```java
import com.lokiscale.bifrost.api.SkillMethod;
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

The sample app loads skills from `classpath:/skills/**/*.yml` and `classpath:/skills/**/*.yaml` and configures named Ollama and OpenAI connections in [application.yml](/C:/opendev/code/bifrost/bifrost-sample/src/main/resources/application.yml).
