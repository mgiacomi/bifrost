---
audience: bifrost-skill-builder
status: development
applies_to: current-repository-checkout
coverage: initial
---

# Bifrost Skill Authoring Knowledge Base

## Purpose

This directory is the AI-first knowledge base for an LLM that collaborates with a developer to design, build, review, and diagnose Bifrost skill trees.

Bifrost is under active development and has no production release yet. These documents describe the current repository checkout. They are deliberately incomplete: a missing topic must not be treated as either unsupported or fully understood.

The intended future consumer is a SkillBuilder application, potentially built with Bifrost itself. Until then, an LLM can use these documents directly while working in the repository.

## Authority and Verification

Use the following evidence order:

1. **This knowledge base** explains intended authoring semantics, design judgment, and recommended patterns.
2. **Focused tests** demonstrate behavior the repository deliberately protects.
3. **Valid and invalid fixtures** demonstrate accepted and rejected manifest shapes.
4. **Samples** demonstrate composition in representative applications.
5. **Production source** resolves exact runtime behavior and edge cases.

The executable framework remains authoritative for what the current checkout accepts and does. If this guide conflicts with tests or production code, do not silently choose one. Report the conflict, identify whether it affects authoring advice, and treat it as documentation drift or a possible framework defect.

Read [source-verification.md](source-verification.md) before performing a source-level investigation.

## How to Route an Authoring Task

| Developer need | Read first | Then read |
| --- | --- | --- |
| Understand Bifrost skill trees | [mental-model.md](mental-model.md) | Relevant topic documents below |
| Design or review a new tree | [checklists/evaluate-a-skill-design.md](checklists/evaluate-a-skill-design.md) | [mental-model.md](mental-model.md) |
| Add evidence-backed output claims | [evidence-contracts.md](evidence-contracts.md) | [mental-model.md](mental-model.md) |
| Resolve ambiguity or an edge case | [source-verification.md](source-verification.md) | The topic's implementation anchors |

Do not load every document by default. Start with the routing entry most relevant to the developer's goal and expand only when the task crosses another documented concern.

## Coverage

| Topic | Coverage | Notes |
| --- | --- | --- |
| Skill-tree mental model | Initial, source-verified | YAML-only public identity, separate Java target registry, mapping, root invocation, visibility, and nesting |
| Evidence contracts | Initial, source-verified | AND semantics, semantic evidence types, multiple producers, enforcement, and nested isolation |
| Source verification | Initial | How an LLM should use guide, tests, fixtures, samples, and production code together |
| Skill-design review | Initial | Cross-cutting questions; not a manifest validator |
| YAML manifest reference | Not yet documented | Inspect current manifest, catalog validation, tests, and samples when required |
| Input contracts | Foundational | The mental model covers mapped Java contract inheritance; complete schema syntax and pure-YAML input behavior are not yet documented |
| Output contracts | Not yet documented | Evidence documentation covers only its interaction with claims |
| Prompts | Not yet documented | Private prompt composition needs a dedicated topic |
| Planning and nested planning | Foundational | The mental model covers choosing direct versus step-based execution; complete planning, retry, and cost semantics are not yet documented |
| Capability visibility and RBAC | Not yet documented | The mental model contains only foundational behavior |
| Attachments and virtual files | Not yet documented | Requires separate source verification |
| Model selection and thinking levels | Not yet documented | Requires separate source verification |
| Execution limits and quotas | Not yet documented | Requires separate source verification |
| Traces and debugging | Not yet documented | Evidence documentation identifies relevant trace behavior only |
| Testing skill trees | Not yet documented | The design checklist gives initial review prompts only |

## Normative Language

These documents use:

- **MUST / MUST NOT** for behavior required by current framework validation, execution semantics, security, or an explicitly stated authoring invariant.
- **SHOULD / SHOULD NOT** for the recommended Bifrost authoring default.
- **MAY** for an optional supported choice.

Each document should distinguish enforced behavior from design guidance and known limitations. Do not present a recommendation as a runtime requirement.

## Development Rules for This Knowledge Base

- Keep ordinary guidance self-contained; source inspection should deepen or verify it, not make it comprehensible.
- Prefer repository-relative links and stable class or method names over line-number links.
- Prefer focused behavioral tests over inferring intent from implementation mechanics.
- Reuse tested fixtures and sample manifests instead of duplicating large examples when practical.
- Mark unresolved discrepancies explicitly.
- Add a topic only after its current semantics have been researched and can be stated without speculation.
- Update the coverage table whenever a topic is added or its confidence changes.

At the first production release, the repository tag should version this knowledge base with the corresponding source and tests.
