# Define the Pre-1.0 Compatibility and Contract Policy Implementation Plan

## Overview

Establish one canonical pre-1.0 compatibility and contract policy in the Bifrost framework design lens, then make each of the five development commands apply the relevant portion of that policy consistently. The change is limited to LLM-facing Markdown guidance: it will distinguish deliberately supported contracts from technical exposure, permit intentional atomic breaks before 1.0, prohibit unexplained compatibility machinery, and define traces as current-run diagnostics rather than durable cross-version formats.

## Current State Analysis

- `ai/thoughts/framework-feature-design-lens.md:9-15` describes the lens as a living document but does not state the repository's current lifecycle or compatibility posture.
- `ai/thoughts/framework-feature-design-lens.md:110-114` recognizes that public features create compatibility cost, but the lens does not distinguish supported application API, supported SPI, documented configuration, durable serialization, ephemeral diagnostics, and accidental implementation exposure.
- `ai/commands/1_research_codebase.md:27-64` requires descriptive codebase research, but gives no method for separating technical visibility from evidence of a supported contract.
- `ai/commands/2_create_plan.md:43-136` requires broad research and skill-authoring impact analysis, but does not require framework plans to classify affected surfaces or record a compatibility decision. Its generic refactoring guidance explicitly says `Maintain backwards compatibility` at `ai/commands/2_create_plan.md:401-405`.
- `ai/commands/3_testing_plan.md:34-81` derives coverage from existing behaviors and tests without explaining that a test records behavior but does not independently create a compatibility promise.
- `ai/commands/4_implement_plan.md:24-45` allows implementation judgment and plan-mismatch handling, but does not prohibit adding unplanned overloads, aliases, fallbacks, adapters, deprecated paths, legacy readers, duplicate interfaces, or dual behavior.
- `ai/commands/5_code_review.md:87-92` broadly treats documentation and tests as compatibility contracts, while `ai/commands/5_code_review.md:129` asks reviewers to check trace/schema compatibility without distinguishing current-version diagnostic coherence from historical readability.
- The downstream public-surface ticket is explicitly blocked on this policy and already uses the intended classification model (`ai/thoughts/tickets/eng-reduce-spring-boot-starter-public-surface.md:5-7`, `:32-42`). Consistent process guidance is therefore a prerequisite for planning that work.

## Desired End State

The design lens contains a clearly isolated, replaceable “Current Pre-1.0 Compatibility Posture” section immediately after “Living Document.” That section is the canonical policy and defines:

1. **Application API**
2. **Supported SPI**
3. **Configuration and manifest contracts**
4. **Persisted or serialized contracts**
5. **Ephemeral diagnostic formats**
6. **Internal or accidentally exposed implementation**

It also defines acceptable evidence for protection, the default atomic-change posture for internal or accidentally exposed surfaces, the stricter assessment required for configuration and manifests, and the current-run-only trace posture.

All five development commands use the same category names and decision sequence:

1. inventory exposure and evidence;
2. classify each affected surface;
3. identify protected consumers and intended breaks;
4. decide explicitly whether a shim is required;
5. update repository consumers atomically or preserve a protected contract as planned;
6. verify that no accidental compatibility mechanism or public surface was introduced.

The commands repeat only role-specific operational rules and link back to the design lens for the canonical policy. No Java, Maven, YAML, runtime configuration, manifest, fixture, sample, or executable test behavior changes.

### Key Discoveries

- The ticket already supplies the complete lifecycle decision, six-category vocabulary, configuration posture, trace posture, per-command requirements, and acceptance criteria (`ai/thoughts/tickets/eng-define-pre-1-compatibility-contract-policy.md:17-160`); no product decision remains unresolved.
- The current contradiction is concrete rather than inferred: blanket preservation appears in planning (`ai/commands/2_create_plan.md:404`), broad test-as-contract treatment appears in review (`ai/commands/5_code_review.md:92`), and historical trace compatibility is not separated from operational trace correctness (`ai/commands/5_code_review.md:129`).
- The design lens is the correct canonical home because it already governs framework judgment, explicitly allows lifecycle-driven revision (`ai/thoughts/framework-feature-design-lens.md:9-15`), and is referenced by the skill-authoring source-verification protocol for intent and future compatibility reasoning (`ai/skill-authoring/source-verification.md:91-101`).
- The skill-authoring knowledge base already states that Bifrost has no production release and describes the current checkout (`ai/skill-authoring/README.md:11-20`). This ticket changes the development process used to evolve the framework, not any behavior a skill author must follow.

## What We're NOT Doing

- Declaring or anticipating the final version 1.0 compatibility policy.
- Planning or implementing the Spring Boot starter public-surface reduction.
- Changing Java visibility, package layout, Spring bean replaceability, constructors, interfaces, runtime DTOs, trace records, trace storage, configuration, or manifest semantics.
- Adding compatibility shims, trace schema versions, migrations, legacy readers, adapters, aliases, fallbacks, deprecated paths, or dual behavior.
- Preserving hypothetical out-of-repository consumers; the ticket establishes that none exist.
- Updating `ai/skill-authoring/` guidance or its coverage table, because no skill-author-facing contract or executable behavior changes.
- Adding automated product tests for prose. Verification will inspect instruction structure, terminology, contradictions, scope, and ticket coverage.

## Skill-Authoring Documentation Impact

**Impact**: No impact

- **Rationale**: The change governs how maintainers and LLMs research, plan, test, implement, and review future framework changes. It does not alter manifest syntax or validation, mappings, defaults, execution or planning semantics, evidence, input/output contracts, capability visibility, RBAC, attachments, model selection, limits, trace output, debugging behavior, or skill-testing behavior in the current checkout.
- **Documents to update**: None.
- **Supporting evidence**: The ticket limits required changes to `ai/thoughts/framework-feature-design-lens.md` and `ai/commands/1_research_codebase.md` through `5_code_review.md`, and requires that no source-code behavior change (`ai/thoughts/tickets/eng-define-pre-1-compatibility-contract-policy.md:64-160`). The knowledge-base coverage table currently marks “Traces and debugging” as not yet documented, and this ticket does not document new author-facing trace behavior (`ai/skill-authoring/README.md:48-66`).
- **Coverage table update**: Not required; no authoring topic, task boundary, evidence confidence, or documented behavior changes.
- **LLM-first usability**: Not applicable to `ai/skill-authoring/`. The process documents themselves will follow the same signal-first principle by centralizing policy in the lens and keeping command-specific instructions direct, concise, and role-specific.

## Contract and Compatibility Impact

This plan applies the ticket's classification model even though the current planning command does not yet require it.

| Surface | Classification and evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | No runtime application entry point changes. No Java or user-facing executable files are in scope. | No break and no compatibility mechanism. |
| Supported SPI | No extension point, Spring override, interface, or provider integration changes. | No break and no compatibility mechanism. |
| Configuration and manifest contracts | No `bifrost.*` property, YAML syntax, validation, default, mapping, or author-facing semantic changes. | Preserve current behavior by leaving executable configuration and manifests untouched. |
| Persisted or serialized contracts | No durable format is changed. The policy will require future work to prove that a format is deliberately durable before preserving it. | No break and no compatibility mechanism. |
| Ephemeral diagnostic formats | The documentation will classify traces as current-run diagnostic formats and protect usefulness, accuracy, ordering, failure visibility, security boundaries, redaction, and current writer/reader/projector/tool coherence. No actual trace schema or storage changes occur in this ticket. | Historical and cross-version readability will be explicitly excluded; no legacy reader, migration, adapter, dual format, or compatibility fixture will be introduced. |
| Internal or accidentally exposed implementation | No implementation surface changes in this ticket. The process default will become atomic removal/update for future deliberately classified internal or accidentally exposed surfaces. | No break in this ticket; future plans must update in-repository consumers atomically instead of adding preservation machinery by default. |

- **Evidence of supported contracts**: The canonical policy will name documentation, an explicit API/SPI allowlist, an approved ticket, and verified consumer usage as evidence. Public visibility, interfaces, constructors, Spring beans, `@ConditionalOnMissingBean`, tests, fixtures, or prior behavior alone will be treated only as evidence of exposure or existing behavior.
- **Intended breaks**: The only intentional break is to the old LLM process assumption that refactoring should preserve all observable behavior. The contradictory blanket instruction and review assumptions will be removed rather than retained alongside the new policy.
- **In-repository consumers to update**: `ai/commands/1_research_codebase.md`, `2_create_plan.md`, `3_testing_plan.md`, `4_implement_plan.md`, and `5_code_review.md` are the complete direct consumers of the new canonical policy. `eng-reduce-spring-boot-starter-public-surface.md` is a downstream ticket, not an implementation file for this change.
- **Public-surface delta**: None in executable Bifrost code. The process gains one canonical policy section, one required framework-plan section, and role-specific operational checks.
- **Shim decision**: **No shim.** Do not retain blanket compatibility guidance, dual decision models, aliases for category names, or fallback instructions. The canonical lens plus concise command rules form one coherent process.

## Implementation Approach

Establish the source of truth first, then update commands in process order. Keep lifecycle-specific policy isolated in the design lens so a future 1.0 policy can replace that section without rewriting enduring design principles. In each command, add only the instructions needed at that stage and reference the lens rather than copying the full policy. Finish with a cross-document audit that maps every ticket acceptance criterion to concrete text and searches for contradictory legacy wording.

## Phase 1: Establish the Canonical Policy in the Design Lens

### Overview

Add a replaceable current-lifecycle policy and integrate contract-focused questions and analysis outputs into the enduring framework design lens.

### Changes Required

#### 1. Add the current pre-1.0 compatibility posture

**File**: `ai/thoughts/framework-feature-design-lens.md`

**Changes**:

- Insert a clearly labeled `Current Pre-1.0 Compatibility Posture` section immediately after `Living Document` and before `North Star`.
- State the positive default: deliberately supported contracts are assessed and protected according to evidence; configuration and manifests are always assessed; current-run diagnostic usefulness and security remain product goals.
- State the negative rule, using the ticket's required sentence or a semantically identical version: public visibility, interfaces, constructors, Spring beans, `@ConditionalOnMissingBean`, existing tests, fixtures, or previous behavior do not independently establish a supported contract.
- Define all six contract categories using the ticket's exact terminology and make classification precede any compatibility decision.
- Define acceptable supporting evidence: documentation, explicit API/SPI allowlists, approved tickets, and verified consumer usage.
- Define the default action for internal or accidentally exposed implementation: prefer one coherent design, remove obsolete paths completely, and update all in-repository callers, tests, samples, fixtures, and documentation atomically.
- Require a protected contract and an explanation of why atomic change is inappropriate before adding overloads, aliases, fallbacks, adapters, deprecated paths, legacy readers, duplicate interfaces, or dual behavior.
- Include the documented configuration/manifest posture: intentional pre-1.0 breaks must be identified in the ticket and plan, explain developer or skill-author impact, update all repository consumers atomically, and include migration machinery only when explicitly required.
- Include the full trace posture: preserve current diagnostic usefulness, coherence, ordering, failure visibility, security boundaries, and redaction while rejecting historical readability, cross-version interchange, schema migration, and compatibility fixtures as defaults.

#### 2. Extend review questions and required analysis output

**File**: `ai/thoughts/framework-feature-design-lens.md`

**Changes**:

- Add a contract-and-compatibility group under `Feature Review Questions` covering affected category, protection evidence, technical exposure versus supported status, public-surface delta, protected consumers, proposed shim form and justification, and removal conditions for any temporary mechanism.
- Extend `Applying the Lens` so framework feature analysis records the classification of every affected surface, intended breaking changes, in-repository consumers to update, public-surface delta, and explicit shim/no-shim decision.
- Keep general design principles outside the lifecycle section unchanged except for the minimal review and analysis hooks needed to apply the canonical policy.

### Success Criteria

#### Automated Verification

- [x] `rg -n "Current Pre-1.0 Compatibility Posture|Application API|Supported SPI|Configuration and manifest contracts|Persisted or serialized contracts|Ephemeral diagnostic formats|Internal or accidentally exposed implementation" ai/thoughts/framework-feature-design-lens.md` finds the lifecycle heading and all six canonical categories.
- [x] `git diff --check -- ai/thoughts/framework-feature-design-lens.md` reports no whitespace errors.

#### Manual Verification

- [x] The lifecycle policy appears immediately after `Living Document` and can be replaced later without rewriting the North Star or feature-design principles.
- [x] Positive protection rules, the negative exposure rule, configuration/manifest posture, trace posture, default internal-surface action, evidence requirements, and named shim forms are explicit and operational.
- [x] Review questions and `Applying the Lens` require recorded decisions rather than asking agents merely to consider compatibility.

---

## Phase 2: Align Research, Planning, and Testing Decisions

### Overview

Update the first three commands so research inventories evidence descriptively, plans make explicit contract decisions, and testing protects only classified contracts without preserving intentionally obsolete behavior.

### Changes Required

#### 1. Separate exposure inventory from contract status during research

**File**: `ai/commands/1_research_codebase.md`

**Changes**:

- Add a framework/API compatibility research rule that links to and uses the canonical categories from the design lens.
- Require separate descriptive inventories for public declarations, interfaces, constructors, Spring beans and `@ConditionalOnMissingBean`, tests and fixtures, documentation, configuration and manifests, serialized formats, traces, and verified in-repository usage.
- Require reports to distinguish technical exposure, evidence of existing behavior, evidence of a deliberately supported contract, and unknown/unclassified status.
- Include the negative rule that visibility, replaceability, tests, or prior behavior alone do not prove a supported API or SPI.
- Preserve the command's documentarian boundary: research reports evidence and current classification status but does not recommend preservation, breakage, or shims.

#### 2. Make contract classification mandatory in framework plans

**File**: `ai/commands/2_create_plan.md`

**Changes**:

- Require reading `ai/thoughts/framework-feature-design-lens.md` completely for Bifrost framework work before forming compatibility conclusions.
- Add contract classification and compatibility assessment to context gathering, research, design-option comparison, and the final-plan completeness checks.
- Add a mandatory `Contract and Compatibility Impact` section to the plan template for framework work. Require it to cover all six categories, supporting evidence, protected consumers, intended breaks, atomic in-repository updates, public-surface delta, and a reasoned shim/no-shim decision.
- Require the section to identify configuration/manifest impact and skill-author impact explicitly when those contracts change.
- Replace `Maintain backwards compatibility` and generic migration language in the refactoring pattern with the pre-1.0 decision model: classify first, preserve deliberately protected contracts, atomically update approved breaks, and do not add compatibility machinery without justification.
- Add final-plan blockers for unresolved contract classification, unexplained compatibility machinery, vague migration placeholders, or an unclassified documented contract.
- Repeat the required negative rule at the point where compatibility decisions are made, while linking to the design lens instead of duplicating its full policy.

#### 3. Test classified boundaries instead of historical behavior by default

**File**: `ai/commands/3_testing_plan.md`

**Changes**:

- Require reading the implementation plan's `Contract and Compatibility Impact` section for framework work and using its classifications to set test scope.
- State that tests prove existing behavior but do not independently create a supported compatibility promise.
- Preserve compatibility-path tests only for protected application API, supported SPI, configuration/manifest, or deliberately durable serialized contracts.
- Require tests that encode an approved obsolete internal behavior to be updated or removed; prohibit requiring old and new behavior simultaneously after an approved break.
- Add boundary tests when application API, supported SPI, or accidental public exposure changes, including checks for signature leaks or unintended extension points where applicable.
- Apply the trace policy by testing current writer/reader/projector/tool coherence, diagnostic usefulness, ordering, failure visibility, security boundaries, and redaction without requiring old trace schemas or historical fixtures to remain readable.
- Extend risk, proposed-test, and exit-criteria templates so protected compatibility paths and intentionally removed obsolete paths are both explicit.

### Success Criteria

#### Automated Verification

- [x] `if (rg -n "Maintain backwards compatibility" ai/commands) { exit 1 }` exits successfully because the blanket instruction is absent.
- [x] `rg -n "Contract and Compatibility Impact" ai/commands/2_create_plan.md ai/commands/3_testing_plan.md` confirms that framework plans define the section and testing plans consume it.
- [x] `git diff --check -- ai/commands/1_research_codebase.md ai/commands/2_create_plan.md ai/commands/3_testing_plan.md` reports no whitespace errors.

#### Manual Verification

- [x] Research remains descriptive and reports exposure, evidence, usage, and classification separately without recommending changes.
- [x] The planning template cannot be finalized with an unresolved category or unexplained overload, alias, fallback, adapter, deprecated path, legacy reader, duplicate interface, or dual behavior.
- [x] Planning explicitly supports intentional documented configuration or manifest breaks only with stated impact and atomic repository updates.
- [x] Testing distinguishes protected compatibility boundaries from obsolete internal behavior and applies the current-run-only trace policy.
- [x] The three commands use the exact six-category terminology from the design lens without synonyms that create a second taxonomy.

---

## Phase 3: Enforce the Classification During Implementation and Review

### Overview

Make implementation and fresh review enforce the approved classifications, prevent accidental compatibility machinery and public-surface growth, and complete a cross-document acceptance-criteria audit.

### Changes Required

#### 1. Make the approved classification an implementation constraint

**File**: `ai/commands/4_implement_plan.md`

**Changes**:

- Require implementers to read and follow the plan's `Contract and Compatibility Impact` section for framework work before editing code.
- State the negative exposure rule at the implementation decision point and direct implementers to the canonical lens.
- Prohibit unplanned overloads, aliases, fallbacks, adapters, deprecated paths, legacy readers, duplicate interfaces, compatibility constructors, bridge types, and dual behavior.
- Require complete removal of intentionally obsolete paths and atomic updates to all in-repository callers, tests, samples, fixtures, configuration, manifests, and documentation identified by the plan.
- Treat discovery of a documented but unclassified contract, a verified protected consumer, or a needed compatibility mechanism as a plan mismatch. Require the existing mismatch/approval flow rather than silently adding a shim.
- Extend phase and final verification with checks for accidental public types, leaked internal types in public API/SPI signatures, and new or retained `@ConditionalOnMissingBean` beans that were not deliberately classified as supported extension points.

#### 2. Classify before reporting compatibility defects in fresh review

**File**: `ai/commands/5_code_review.md`

**Changes**:

- Restructure the API/compatibility review lens so the reviewer first inventories the changed surface and evidence, classifies it using the six canonical categories, and only then evaluates preservation or breakage.
- Replace the statement that documentation and tests are automatically contracts with the evidence-based positive and negative rules from the policy.
- Distinguish an approved pre-1.0 break of internal or accidentally exposed behavior from a regression of a deliberately protected contract.
- Add maintainability findings for unjustified shims, retained obsolete behavior, dual paths, accidental public types, leaked internal signature types, and unclassified `@ConditionalOnMissingBean` extension points.
- Limit compatibility-path test expectations to protected surfaces and require confirmation that approved obsolete behavior was removed rather than hidden behind a fallback.
- Replace generic trace/schema compatibility expectations with the canonical ephemeral-diagnostic policy: current-version coherence, usefulness, ordering, failure visibility, security, and redaction are review obligations; historical or cross-version readability is not.
- Extend requirements conformance, candidate-finding verification, output, and final checklist language as needed so approved breaks are not reported as defects and unexplained preservation is still actionable.

#### 3. Perform a cross-document consistency and scope audit

**Files**:

- `ai/thoughts/framework-feature-design-lens.md`
- `ai/commands/1_research_codebase.md`
- `ai/commands/2_create_plan.md`
- `ai/commands/3_testing_plan.md`
- `ai/commands/4_implement_plan.md`
- `ai/commands/5_code_review.md`

**Changes**:

- Compare every ticket acceptance criterion with the final diff and record the implementing section or verification evidence.
- Confirm the design lens remains the only full statement of the policy and commands contain concise, action-specific rules rather than copied policy blocks.
- Normalize category names and decision vocabulary across all six documents.
- Search for residual instructions that infer protection from visibility, replaceability, tests, traces, or previous behavior; remove or qualify any contradictions.
- Confirm no executable files or skill-authoring documents changed.

### Success Criteria

#### Automated Verification

- [x] `rg -n "overloads|aliases|fallbacks|adapters|deprecated paths|legacy readers|duplicate interfaces|dual behavior" ai/commands/4_implement_plan.md ai/commands/5_code_review.md` confirms common shim forms are named at enforcement points.
- [x] `rg -n "Application API|Supported SPI|Configuration and manifest contracts|Persisted or serialized contracts|Ephemeral diagnostic formats|Internal or accidentally exposed implementation" ai/thoughts/framework-feature-design-lens.md ai/commands` supports the manual vocabulary audit across the canonical policy and commands.
- [x] `if (rg -n "Treat compatibility promises in documentation and tests as contracts" ai/commands/5_code_review.md) { exit 1 }` exits successfully because the obsolete rule is absent.
- [x] `git diff --check -- ai/thoughts/framework-feature-design-lens.md ai/commands` reports no whitespace errors.
- [x] `git diff --name-only -- '*.java' '*.kt' '*.xml' '*.yml' '*.yaml' '*.json'` produces no output, confirming that no executable source, build, runtime configuration, manifest, fixture, or sample file changed.

#### Manual Verification

- [x] Every acceptance criterion in the ticket maps to explicit final text in the lens or a command.
- [x] All commands distinguish approved intentional breaks from regressions of protected contracts.
- [x] Internal or accidentally exposed surfaces default to coherent atomic change; documented configuration and manifests always receive explicit impact analysis; durable serialization is preserved only when deliberately classified; traces preserve current usefulness and security without historical compatibility.
- [x] New public types, leaked internal signature types, and `@ConditionalOnMissingBean` extension points must be deliberately classified during implementation and review.
- [x] Unjustified compatibility machinery and retained obsolete behavior are actionable maintainability risks.
- [x] The final diff is limited to the six required Markdown files plus normal plan-checkbox updates; there are no changes to `ai/skill-authoring/` or executable behavior.

## Testing Strategy

### Automated Documentation Checks

- Use focused `rg` assertions to confirm the lifecycle heading, six canonical categories, mandatory plan section, named shim forms, and removal of the two known contradictory instructions.
- Run `git diff --check` across every edited document.
- Inspect `git diff --name-only` with executable/build/configuration extensions to enforce the ticket's no-source-behavior-change boundary.
- A dedicated `3_testing_plan.md` artifact is not necessary for this documentation-only policy change: there is no runtime behavior, failing product test, fixture, or integration path to design. The executable checks and manual acceptance mapping above are the complete verification plan.

### Unit Tests

- None. The implementation changes no executable unit and prose-only tests would couple to wording rather than behavior.

### Integration Tests

- None. The implementation changes no runtime integration.

### Manual Testing Steps

1. Read the final design-lens policy as a standalone source of truth and verify that it answers classification, evidence, breaking-change, configuration/manifest, trace, and shim questions without consulting the commands.
2. Walk one hypothetical internal Java refactor and one documented configuration change through research, planning, testing, implementation, and review; confirm each command produces a consistent decision without defaulting to a compatibility shim.
3. Walk a trace schema change through testing and review; confirm current-version diagnostic coherence and sensitive-data safeguards remain required while historical readability is not.
4. Map all twelve ticket acceptance criteria to the final diff and confirm that no criterion relies on implied wording.

## Performance Considerations

None. The change affects Markdown instructions only. Keep command additions concise and role-specific so routine LLM context consumption does not grow through repeated copies of the canonical policy.

## Migration Notes

There is no runtime or data migration. The process transition is atomic: remove the old blanket compatibility instructions in the same change that adds the canonical policy and all role-specific decision rules. Do not retain both old and new guidance during a transition period. The lifecycle section is intentionally isolated so a future version 1.0 policy can replace it without rewriting enduring design principles.

## References

- Original ticket: `ai/thoughts/tickets/eng-define-pre-1-compatibility-contract-policy.md`
- Canonical design lens to update: `ai/thoughts/framework-feature-design-lens.md`
- Development commands: `ai/commands/1_research_codebase.md`, `ai/commands/2_create_plan.md`, `ai/commands/3_testing_plan.md`, `ai/commands/4_implement_plan.md`, `ai/commands/5_code_review.md`
- Downstream blocked work: `ai/thoughts/tickets/eng-reduce-spring-boot-starter-public-surface.md`
- Skill-authoring routing and lifecycle context: `ai/skill-authoring/README.md`
- Source-verification treatment of tests and future compatibility intent: `ai/skill-authoring/source-verification.md`
- Planning metadata: 2026-07-15 13:37:30 PDT; commit `debde20084bae02b77fc3339290a30f3b708e9b7`; branch `main`; repository `bifrost`
