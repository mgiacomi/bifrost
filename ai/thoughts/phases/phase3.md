# Phase 3 - YAML Manifest System & Spring Resources

## Goal
Establish the YAML-driven skill model as a Spring-native manifest system, exposing skills from configurable resource locations and registering them as runtime capabilities during application startup.

## Primary Outcomes
- YAML manifests are discovered from Spring `Resource` locations and loaded during application context initialization.
- YAML-defined skills are validated, mapped to runtime metadata, and registered into the `CapabilityRegistry`.
- Current manifest properties such as model selection, thinking level, RBAC, allowed skill visibility, mapping targets, and `planning_mode` are supported natively.

## Scope
- YAML schema and strong typing to domain models.
- Resource-pattern-based discovery and loading.
- Boot-time validation and linkage for current manifest fields.
- Mapping model preferences, RBAC, allowed skills, mapping targets, and `planning_mode` properties natively.

## Out of Scope
- Rich structured prompt authoring fields such as `persona`, `logic_steps`, or `input_context`.
- Linter configuration and enforcement.
- Spring AI `PromptTemplate` adoption as a primary goal for this phase.

## Detailed Tasks
### 1. Declarative parsing
- Utilize Jackson/SnakeYAML configured via standard Spring mechanics to load `SkillManifest` records.
- Discover `yml` files using a configured Spring `ResourcePatternResolver`.

### 2. Validation & Linkage
- Ensure loaded manifests trace correctly to existing `target_id` beans.
- Validate required fields and model/thinking compatibility during boot to fail fast.
- Reject duplicate skill names and invalid mapped targets during startup.

### 3. Runtime Registration
- Convert loaded YAML manifests into runtime skill definitions and register them into the `CapabilityRegistry`.
- Preserve YAML-facing capability names and descriptions even when a manifest maps to a deterministic Spring target.
- Keep YAML manifest configuration as the source of truth for runtime visibility and execution metadata.

## Deliverables
- Declarative YAML file loader mechanism.
- Validated boot-time mapping between YAML and code targets.
- Runtime registration of YAML-backed capabilities with the current manifest field set.

## Suggested Tickets
- `ENG-010` - YAML skill resource discovery and catalog loading
- `ENG-011` - YAML skill manifest validation and execution config resolution
- `ENG-012` - YAML skill capability registration and mapped target linkage
- `ENG-013` - YAML skill runtime metadata and visibility controls

## Ticket Boundary Notes
- `ENG-010` owns Spring resource discovery and typed catalog loading through `YamlSkillCatalog`.
- Runtime registration, `mapping.target_id` linkage, RBAC flow, and visibility behavior are follow-on concerns for `ENG-012` and `ENG-013`.

## Exit Criteria
- YAML files dropped into the resource folder are automatically usable as tools.
- Invalid YAML, broken links, duplicate names, unknown models, or unsupported thinking levels fail the Spring application context load properly.
