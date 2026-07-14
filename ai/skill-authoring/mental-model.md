---
audience: bifrost-skill-builder
status: development
applies_to: current-repository-checkout
coverage: initial
---

# Bifrost Skill-Tree Mental Model

## Purpose

Use this document to establish shared vocabulary and choose the broad shape of a Bifrost skill tree. It is not a complete YAML manifest reference.

## Core Model

Bifrost combines public model-driven YAML skills with internal deterministic Spring implementation targets. A skill tree is a hierarchy of YAML capability boundaries, not a static rule tree: an LLM-backed skill reasons about its mission and can invoke only the child YAML capabilities exposed to it.

The normal shape is:

```text
Application code
    -> entry YAML skill
        -> LLM-backed planning or reasoning skill
            -> nested YAML specialist
                -> mapped YAML leaf
                    -> deterministic Java @SkillMethod
```

Not every tree needs every level. A simple skill may be one LLM-backed YAML skill, and a shallow workflow may have one planner with deterministic leaves.

## Capability Types

### Entry YAML skill

An entry skill is the YAML skill invoked by application code through `SkillTemplate`.

Current behavior:

- `SkillTemplate` accepts a YAML skill name and an object or map input.
- The root input is normalized and validated against the skill's effective input contract before execution.
- Every invocation receives a new Bifrost session.
- `SkillTemplate` does not provide the supported public entry path for a raw Java capability; expose Java behavior through a mapped YAML skill when it must be an entry skill.

The application developer SHOULD be able to determine how to invoke an entry skill from that skill's input contract without understanding the tree below it.

### LLM-backed YAML skill

A YAML skill without `mapping.target_id` is model-driven.

It may:

- receive structured mission input;
- use its `description` as public capability information;
- use an optional private `prompt` for its own reasoning behavior;
- call child capabilities exposed through `allowed_skills`;
- declare input, output, evidence, linter, model, RBAC, and execution settings supported by the current manifest;
- enable the step-based planning executor by explicitly setting `planning_mode: true`.

An LLM-backed skill does not automatically see every registered capability. Its tool surface is governed by its local `allowed_skills` and runtime access checks.

### Mapped YAML skill

A mapped YAML skill declares `mapping.target_id` and delegates execution to a registered Java `@SkillMethod` target.

The YAML capability supplies the tree-facing name, description, contract, and access policy. Java supplies deterministic implementation behavior.

A mapped YAML skill:

- is still registered as a YAML capability;
- can be invoked by `SkillTemplate` using its YAML name;
- can appear in another YAML skill's `allowed_skills`;
- invokes its Java target instead of an LLM;
- MUST NOT declare a private `prompt`, because there is no model execution at that capability boundary;
- inherits the Java input contract when it does not declare `input_schema`;
- in the current checkout, may declare `input_schema` only when it is structurally compatible with the Java target.

Use a mapped YAML skill when deterministic Java behavior must participate in the YAML skill tree or be exposed as an entry skill.

#### Prefer reflected input-contract inheritance for mapped skills

Omitting `input_schema` from a mapped YAML skill is an explicit authoring choice, not an absence of validation. Bifrost derives the effective public input contract from the mapped Java target's reflected tool schema. The generated tool descriptor and entry-input validation use that inherited contract.

The Java method signature and parameter metadata therefore MUST describe the input that callers and parent planners are allowed to supply. Verify requiredness, field names, types, descriptions, nested shapes, and runtime markers at the Java boundary rather than assuming reflection will express the intended public contract.

A mapped skill SHOULD omit `input_schema`. Do not copy a schema into every mapped wrapper merely to repeat the reflected fields. Repetition creates a second contract that must remain compatible and can drift without adding meaning.

The current compatibility validator requires an explicitly declared mapped schema to preserve the Java contract's structure, including its properties, required fields, types, enums, formats, collection shapes, and additional-property behavior. It does not provide a general input-narrowing or adaptation mechanism. The proposed pre-release correction will reject `input_schema` on mapped skills and make reflected Java input the only mapped contract source.

If two public mapped capabilities need genuinely different input shapes, use separate deterministic Java adapter targets so transformation and validation remain explicit and testable.

This inheritance applies only across an explicit YAML-to-Java mapping. It does not imply automatic inheritance of business inputs between parent and child YAML missions.

**Current-checkout limitations:** `YamlSkillCatalog` still requires a valid `model` on every YAML manifest, including a mapped skill whose Java execution never uses that model, and it still accepts a structurally compatible duplicate mapped `input_schema`. Treat these as temporary catalog behaviors. The proposed correction is tracked in [`eng-simplify-mapped-yaml-skill-manifests.md`](../thoughts/tickets/eng-simplify-mapped-yaml-skill-manifests.md). Until that change is implemented, manifests must continue to satisfy current executable validation, although authors SHOULD already omit mapped `input_schema` because inheritance is supported today.

### Java `@SkillMethod`

`@SkillMethod` registers deterministic Spring method behavior as an internal implementation target keyed by `beanName#methodName`. It does not declare a public name or enter the public `CapabilityRegistry`; the reflected method signature supplies the target schema and input contract.

Annotated method names MUST be unique within one Spring bean because overloads would produce the same target ID. Multiple independently named and governed YAML skills MAY map to one target. Spring proxy, interface, and bridge methods are canonicalized, and invocation resolves the final bean by name so configured advice remains active.

`CapabilityRegistry`, `SkillTemplate`, and `allowed_skills` expose YAML names only. Wrap a Java method with a mapped YAML skill whenever application code or an LLM-backed skill must call it. A target ID is trusted mapping/diagnostic metadata, never a public invocation alias.

Java leaves SHOULD own operations whose correctness should not depend on model reasoning, such as database lookups, calculations, controlled API calls, policy enforcement, and stable fixture access.

## Root, Planner, Specialist, and Leaf Are Roles

These words describe how a capability is used, not distinct framework classes.

- **Root or entry skill:** invoked by application code.
- **Planner:** an LLM-backed YAML skill with `planning_mode: true` that creates and executes a bounded task plan.
- **Specialist:** a child YAML skill responsible for a narrower mission. It may itself be a planner.
- **Leaf:** a capability that performs work without further decomposition, commonly a mapped deterministic Java capability.
- **Synthesis skill:** a skill whose primary responsibility is composing evidence and results into the final output.

A YAML skill can be a root in one tree and a nested specialist in another if its contract and visibility boundaries make sense in both contexts.

## Visibility Is Local

`allowed_skills` defines the candidate child YAML capabilities for one LLM-backed skill. It is not transitive.

If a root allows `investigateNetwork`, and `investigateNetwork` allows `checkDns`, the root sees `investigateNetwork`; it does not automatically see `checkDns`.

Runtime authorization filters the declared tool surface again. Visibility is not authorization by itself, and authorization is enforced at execution as well as discovery.

A skill SHOULD expose only the capabilities needed for its responsibility. Narrow tool surfaces improve security, make plans easier to understand, and preserve HTN boundaries.

## Nested Execution Preserves Capability Boundaries

When an LLM-backed YAML skill invokes another LLM-backed YAML skill:

- the child receives the arguments supplied for the child contract;
- the child opens its own mission frame inside the current session;
- the child uses its own model, prompt, allowed skills, plan, and evidence contract;
- the parent's plan and evidence ledger are saved and restored around the child mission;
- the parent observes the child capability result, not the child's internal tool surface or evidence ledger.

This isolation is intentional. A parent contract should describe the child capability it invokes rather than coupling itself to the child's internal leaves.

## Choose Direct Reasoning or Step-Based Planning Deliberately

The current execution coordinator selects the step-based planning executor only when the YAML manifest explicitly declares:

```yaml
planning_mode: true
```

Do not assume that a global planning default turns an undeclared skill into a step-loop planner in the current implementation.

An LLM-backed YAML skill without `planning_mode: true` uses direct mission execution. This remains true when the skill is nested. A nested specialist therefore does not need to become a planner merely because it is part of a deeper tree.

Use direct mission execution when one focused model-directed mission interaction is appropriate and the skill does not need Bifrost's explicit plan-and-step lifecycle. The direct executor still receives the skill's visible tools, so direct execution does not mean that the skill must have no children. It means Bifrost does not first create a task plan and then ask the model for one bounded step action at a time.

Direct execution is the normal choice for a focused specialist that does not benefit from an explicit plan. Do not interpret "direct" as a guarantee of exactly one physical provider request: tool-calling protocols, advisors, and provider behavior may involve additional internal interactions.

Use step-based planning when the skill genuinely needs dynamic decomposition, selective child-capability use, or multi-step progress toward its result. Planning adds an initial plan-model interaction and a bounded model-driven step loop. A planning step may invoke a nested skill, and that child independently chooses direct or planning execution from its own manifest.

Each additional planning boundary increases potential model calls, latency, validation retries, and failure locations. Nesting itself is not a reason to avoid a useful planner, but every planning level SHOULD provide a meaningful mission boundary and decomposition benefit.

Do not select direct execution merely to conceal that a mission needs decomposition, and do not select planning merely because it appears more capable. Choose the least complex execution semantics that truthfully fit the skill's responsibility.

A planner SHOULD have:

- a narrow mission;
- a bounded `max_steps` appropriate to the mission;
- a deliberate `allowed_skills` surface;
- explicit input and output contracts when callers depend on structured behavior;
- evidence requirements only for claims that need deterministic supportability enforcement.

A direct specialist SHOULD have:

- one focused reasoning or synthesis responsibility;
- a contract it can fulfill without an explicit, framework-managed task plan and step loop;
- a narrow `allowed_skills` surface if direct tool or child-skill use is part of that responsibility;
- explicit input and output contracts when callers depend on structured behavior;
- model and validation settings appropriate to that responsibility.

Sample claims about model compatibility MUST be scoped to the sample and configuration actually tested. Success on a shallow direct skill does not establish that the same model can reliably execute a multi-level planning tree. Bifrost skill architecture SHOULD target capable production models; small local models may be useful for experimentation but are not a reason to weaken planning semantics or runtime safeguards.

## Inputs, Runtime Metadata, and Evolving State

Keep these categories distinct when designing a tree:

- **Business input:** mission data such as ticket text, identifiers, dates, requested actions, and scenario names. It normally travels through declared skill and tool inputs.
- **Trusted execution metadata:** authoritative identity, authorization, tenant, correlation, deadline, or provenance information. It should not become model-controlled merely for propagation convenience. No general framework feature for arbitrary trusted metadata is documented here.
- **Evolving mission state:** plans, tool results, evidence, artifacts, and trace records. Use the framework concept appropriate to the information rather than inventing an ambient mutable bag.

Do not automatically treat repeated business inputs as runtime metadata. Explicit contracts preserve local comprehension and traceability.

## Initial Design Heuristics

- Keep the entry contract obvious to application developers.
- Give each skill one coherent responsibility.
- Use the model for decomposition, selection, interpretation, and synthesis.
- Use deterministic Java leaves for controlled side effects and operations with programmatic correctness rules.
- Keep child visibility local and narrow.
- Preserve child abstraction boundaries; do not make parents depend on internal leaves.
- Prefer explicit business inputs over implicit inheritance.
- Add contracts when the framework can enforce a meaningful invariant, not merely to decorate a manifest.
- Validate a proposed shape against representative successful and failure branches.

## Implementation Anchors

Use these only when current behavior or an edge case needs verification:

- [`SkillTemplate.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/SkillTemplate.java) defines the public invocation surface.
- [`DefaultSkillTemplate.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java) validates YAML-only entry invocation and creates sessions.
- [`YamlSkillManifest.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java) defines the accepted manifest object shape.
- [`YamlSkillDefinition.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillDefinition.java) exposes normalized YAML skill settings.
- [`YamlSkillCapabilityRegistrar.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java) registers pure and mapped YAML capabilities.
- [`SkillImplementationTarget.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillImplementationTarget.java) defines internal Java target metadata without provider-facing tool identity.
- [`SkillImplementationTargetRegistry.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillImplementationTargetRegistry.java) defines the internal `beanName#methodName` registry boundary.
- [`SkillMethodBeanPostProcessor.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java) discovers canonical annotated methods and builds proxy-safe target invokers.
- [`YamlSkillCapabilityRegistrarTests.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrarTests.java) covers equal YAML/Java names, shared targets, contract inheritance, advice, errors, and public metadata.
- [`SkillInputContractResolver.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java) selects explicit YAML, inherited Java, or generic input contracts.
- [`DefaultSkillVisibilityResolver.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/DefaultSkillVisibilityResolver.java) defines the current local YAML child surface and access filtering.
- [`CapabilityExecutionRouter.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java) distinguishes nested LLM-backed YAML execution from mapped/Java invocation and preserves parent state.
- [`ExecutionCoordinator.java`](../../bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java) selects the execution engine and constructs each YAML mission boundary.
- [`CapabilityExecutionRouterTest.java`](../../bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/CapabilityExecutionRouterTest.java) covers nested routing, authorization fallback, plan restoration, evidence isolation, and canonical mission input.

## Coverage Boundary

This document does not define every manifest field or the complete behavior of planning, inputs, outputs, RBAC, attachments, retries, quotas, or traces. Consult [README.md](README.md) for current topic coverage before advising on those areas.
