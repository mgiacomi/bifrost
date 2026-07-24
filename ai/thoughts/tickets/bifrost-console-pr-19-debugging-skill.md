# PR 19 — Portable Debugging Skill and Interoperability

## Status

Proposed ticket brief. Depends on PR 18.

## Outcome

Ship the portable `bifrost-runtime-debugging` Agent Skill and demonstrate that
representative IDE-based LLMs can use the MCP surface to produce evidence-backed,
appropriately uncertain runtime explanations.

## In scope

- Create the portable Agent Skill package and focused MCP operation guide.
- Define required and optional named Bifrost capabilities and bootstrap behavior.
- Teach workflow selection, progressive disclosure, stable identifier citation,
  evidence/interpretation separation, uncertainty, and safe handling of untrusted
  runtime content.
- Cover failed execution, slow execution, high usage, and unfamiliar nested
  skill-path workflows.
- Add representative IDE/client evaluations, adversarial-content evaluations,
  MCP-without-skill and skill-without-MCP cases, optional-capability degradation,
  and missing-required-capability guidance.
- Complete distribution, versioning, installation, release documentation, and
  final Phase 3 conformance evidence.

## Guardrails

- The skill is procedural guidance, not a security boundary or deterministic
  computation engine.
- Do not claim that Go controls IDE tools, unrelated repository access, or model
  obedience.
- Do not add scripts initially or assume automatic installation into every IDE.
- Runtime-to-workspace correlation is LLM reasoning with explicit uncertainty;
  `sourcePath` is not a local filesystem locator or provenance claim.
- The skill package version is distribution metadata, not a runtime gate.

## Acceptance signals

- A representative agent investigates each workflow and produces an
  evidence-backed explanation with stable identifiers, direct limitations, and
  no fabricated causal certainty.
- Representative evaluations reference the applicable approved workflow or most
  specific requirement IDs.
- MCP remains independently usable and the skill fails safely without MCP.
- Missing protocol, Bifrost capability, target compatibility, authentication,
  and evidence availability remain distinct.
- Adversarial runtime instructions produce no claimed server-side enforcement
  beyond the actual Go boundary.

## Detailed-planning focus

Use the repository skill-creation process; research representative client setup,
Agent Skills packaging, capability discovery instructions, evaluation fixtures
and rubrics, distribution location, documentation evidence, and skill-authoring
knowledge-base routing.

## Out of scope

Console-owned LLM analysis, write/control tools, sampling, elicitation, remote
MCP, IDE auto-installation, and guaranteed model behavior.
