# Phase 3 - YAML Manifest System & Spring Resources

## Goal
Implement the YAML-driven skill model, exposing skills as configurable Spring Resources (`classpath:skills/*.yml`, `file:/skills/*.yml`).

## Primary Outcomes
- YAML manifests are loaded seamlessly into the `CapabilityRegistry` during Spring application context initialization.
- Dynamic Sub-agents defined in YAML correctly stitch together inputs, personas, and steps into prompts sent to Spring AI.

## Scope
- YAML schema and strong typing to domain models.
- Prompt Assembly logic mapped to Spring AI `PromptTemplate`.
- Mapping tags, linter rules, model preferences, and `planning_mode` properties natively.

## Detailed Tasks
### 1. Declarative parsing
- Utilize Jackson/SnakeYAML configured via standard Spring mechanics to load `SkillManifest` records.
- Discover `yml` files using a configured Spring `ResourcePatternResolver`.

### 2. Prompt Assembly Engine
- Utilize Spring AI's `PromptTemplate` and `SystemPromptTemplate` to inject the variables provided by the `input_context` mapping of the YAML manifest.

### 3. Validation & Linkage
- Ensure loaded manifests trace correctly to existing `target_id` beans.
- Apply bean validation (`@Valid`, constraints) to YAML structures during boot to fail fast.

## Deliverables
- Declarative YAML file loader mechanism.
- Validated boot-time mapping between YAML and code targets.
- Dynamic rendering of Spring AI `Prompt` sequences based on manifests.

## Exit Criteria
- YAML files dropped into the resource folder are automatically usable as tools.
- Invalid YAML or broken links fail the Spring Application context load properly.
