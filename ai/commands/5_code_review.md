---
description: Perform an independent, evidence-based code review of a change and validate it against requirements
---

# Code Review

You are tasked with performing a rigorous code review from a **fresh context**. Review the resulting code independently for correctness, safety, compatibility, maintainability, and adequate verification. If a ticket, implementation plan, or testing plan exists, also validate conformance to those requirements, but do not let the plan limit the defects or risks you look for.

## Core review principles

1. **Review the code, not the story.** Reconstruct the change from Git and the repository. Do not trust implementation summaries, plan checkmarks, or claimed test results without verification.
2. **Inspect independently before comparing with the plan.** First ask whether the code is correct. Only afterward ask whether it matches the ticket and plan. A plan can be incomplete or wrong.
3. **Findings come first.** The primary output is actionable defects and regressions, ordered by severity. Plan conformance and successful checks are supporting information.
4. **Use concrete evidence.** Every finding must identify the affected code, the triggering condition or execution path, and the resulting impact.
5. **Be skeptical, not speculative.** Verify candidate findings against surrounding code, callers, tests, framework behavior, and existing safeguards before reporting them.
6. **Review by risk.** Select review lenses based on the actual diff. Do not force irrelevant database, UI, or security-framework checklists onto unrelated changes.
7. **Security is always considered.** Do not limit security review to concerns mentioned in the plan; omissions in requirements can themselves be findings.
8. **Passing tests do not prove correctness.** Evaluate whether tests exercise the changed behavior, failure modes, compatibility boundaries, and meaningful assertions.
9. **Do not modify the implementation during review.** Report findings and evidence. Only implement fixes when the user explicitly asks for them.

## Inputs

The command may be invoked with any combination of:

- ticket or requirements document;
- implementation plan;
- testing plan;
- branch, commit, pull request, or comparison base;
- working-tree changes.

If the user supplies a file, read it **completely** before broader investigation. If no plan exists, perform a general code review from the available requirements and diff. A plan is helpful context, not a prerequisite.

## Review process

### Step 1: Establish the exact review scope

Assume no prior conversational context. Determine exactly what changed before evaluating it.

1. Read repository instructions such as `AGENTS.md` and any directly supplied ticket, plan, or testing-plan files.
2. Inspect repository and working-tree state:

   ```bash
   git status --short
   git branch --show-current
   git diff --stat
   git diff
   git diff --cached --stat
   git diff --cached
   ```

3. Include untracked files in the review by locating and reading them; `git diff` does not show their contents.
4. If reviewing committed branch work, determine the correct comparison base from the user-provided base, upstream, or repository default branch. Use the merge base and inspect both the summary and full diff. Do not assume the base branch is named `main`.
5. Distinguish:
   - committed branch changes;
   - staged changes;
   - unstaged changes;
   - untracked files;
   - unrelated pre-existing working-tree changes.
6. Produce a concise internal inventory of changed production code, tests, configuration, documentation, migrations, dependencies, generated files, and public interfaces.

Do not review only the diff hunks. Read enough surrounding code and connected callers/callees to understand the changed behavior.

### Step 2: Reconstruct intended and actual behavior

1. Read the changed files fully when practical. For large files, read every changed region with sufficient surrounding context and inspect all relevant types and methods it depends on.
2. Trace important execution paths across configuration binding, construction/wiring, runtime calls, persistence, serialization, external integrations, and cleanup as applicable.
3. Locate existing tests and nearby implementation patterns.
4. Identify:
   - the behavior that existed before;
   - the behavior introduced by the change;
   - public or operational contracts affected;
   - trust boundaries and failure boundaries;
   - assumptions the implementation relies on.
5. Use the ticket and plans to understand intent, but record discrepancies between those documents and executable behavior rather than silently choosing one.
6. For Bifrost framework changes, read the canonical policy in `ai/thoughts/framework-feature-design-lens.md`. Inventory exposure and evidence, then classify each affected surface as Application API, Supported SPI, Configuration and manifest contracts, Persisted or serialized contracts, Ephemeral diagnostic formats, or Internal or accidentally exposed implementation before evaluating compatibility.

### Step 3: Perform an independent defect review

Perform this step **before** plan-conformance validation. Select all applicable review lenses.

#### Functional correctness

- Verify normal behavior, boundary conditions, invalid inputs, empty/null states, partial state, ordering, retries, idempotency, and recovery paths.
- Trace every changed branch to its externally visible result.
- Look for behavior that succeeds locally but fails when composed with callers or downstream consumers.
- Check whether error handling preserves the original failure, produces actionable diagnostics, and avoids inconsistent state.

#### API and compatibility

- First separate technical exposure and existing behavior from evidence of a deliberately supported contract. Documentation, an explicit API/SPI allowlist, an approved ticket, and verified consumer usage can establish protection.
- A public modifier, interface, constructor, Spring bean, `@ConditionalOnMissingBean`, existing test, fixture, or previous implementation does not by itself establish a supported contract.
- For deliberately protected Application API, Supported SPI, Configuration and manifest contracts, and Persisted or serialized contracts, check source, binary, configuration, serialization, schema, stored-data, CLI, and extension-point compatibility as applicable.
- Verify defaults and any explicitly required migration behavior. For an approved pre-1.0 break, verify atomic repository updates rather than assuming mixed old/new behavior is required.
- Identify silent behavior changes, ambiguous precedence, renamed fields, altered validation order, and changed exception contracts.
- Do not report an approved break of Internal or accidentally exposed implementation as a compatibility defect. Do report regressions of deliberately protected contracts and breaks that lack the required classification, evidence, impact assessment, or approval.

#### Security and privacy

- Identify trust boundaries and attacker-controlled inputs.
- Check authorization, authentication, injection, path handling, deserialization, request forgery, unsafe redirects, and privilege changes where relevant.
- Check secrets, tokens, credentials, headers, personal data, and sensitive payloads for exposure through logs, traces, metrics, exceptions, object rendering, or persistence.
- Verify secure defaults and ensure new configuration cannot bypass established controls.
- Do not report generic security concerns without a concrete path through the changed code.

#### Concurrency and lifecycle

- Check shared mutable state, publication, thread safety, races, deadlocks, blocking work, cancellation, and timeout behavior.
- Verify clients, executors, streams, files, connections, and other resources are created at the correct scope and closed appropriately.
- Look for per-request construction of expensive reusable objects and unsafe reuse of request-scoped objects.
- Consider application startup, shutdown, refresh, and failure during initialization.

#### Persistence and migrations

- When applicable, verify forward and rollback safety, transaction boundaries, data preservation, constraints, indexes, backfills, ordering, and compatibility during rolling deployment.
- Check behavior with existing production data, not only empty schemas.

#### External services and distributed behavior

- Check timeouts, retries, rate limits, duplicate effects, partial responses, malformed responses, protocol compatibility, fallback behavior, and error translation.
- Verify endpoint/account/tenant routing cannot cross boundaries.
- Check whether retry or fallback behavior can multiply cost or side effects.

#### Performance and resource bounds

- Look for unbounded collections, recursion, payloads, retries, concurrency, cardinality, or work amplification.
- Check hot paths for avoidable repeated construction, network calls, parsing, reflection, or full scans.
- Require a concrete workload or scale trigger before reporting performance findings.

#### Observability and operations

- Verify logs, metrics, traces, and errors identify the failing operation without leaking sensitive data.
- Check metric-tag cardinality. For Ephemeral diagnostic formats, verify current writer/reader/projector/debugging-tool coherence, usefulness, accuracy, ordering, failure visibility, security boundaries, and redaction. Historical or cross-version readability is not required unless a ticket explicitly changes the canonical policy.
- Confirm operators can distinguish multiple configured instances, tenants, models, or endpoints when the change introduces them.
- Verify failures at startup and runtime are diagnosable.

#### Maintainability and repository fit

- Check ownership boundaries, naming, duplication, extension points, and consistency with existing patterns.
- Report maintainability concerns only when they create a concrete risk of misuse, divergence, or future defects; do not report stylistic preferences as findings.
- Check dependency, build, packaging, and deployment changes for necessity and unintended scope.
- Treat unjustified overloads, aliases, fallbacks, adapters, deprecated paths, legacy readers, duplicate interfaces, compatibility constructors, bridge types, dual behavior, and retained obsolete paths as actionable maintainability risks.
- Check for accidental public types, internal types leaked through public Application API or Supported SPI signatures, and new or retained `@ConditionalOnMissingBean` beans not deliberately classified as Supported SPI.

#### Documentation and user-facing semantics

- Verify configuration examples, migration notes, API documentation, samples, and troubleshooting guidance match executable behavior.
- Identify stale or missing documentation when users or operators must act differently after the change.
- Verify examples do not encourage insecure credential handling or unsupported behavior.

### Step 4: Evaluate tests and run verification

1. Map each changed behavior and primary risk to existing or new tests.
2. Review test quality:
   - Does the test fail without the implementation?
   - Does it assert externally meaningful behavior rather than implementation details?
   - Does it cover negative and compatibility paths?
   - Can mocks hide the integration defect being tested?
   - Are assertions strong enough to detect incorrect routing or state?
   - Are compatibility-path tests limited to protected surfaces, and do tests confirm approved obsolete behavior was removed rather than hidden behind a fallback?
3. Compare implemented tests with the dedicated testing plan when present. The testing plan is expected coverage, not a ceiling.
4. Run the narrowest relevant tests first, followed by the module or repository verification appropriate to the change.
5. Record exact commands and results. Do not state that a check passed unless it was run successfully in this review context.
6. If a check cannot be run, state why and describe the residual risk. Do not convert “not run” into “pass.”
7. Distinguish failures caused by the reviewed change from unrelated environment or pre-existing failures, with evidence.

### Step 5: Validate requirements and plan conformance

After the independent review:

1. Map every ticket acceptance criterion and plan success criterion to code, tests, documentation, or manual verification evidence.
2. Verify completed plan checkboxes against the repository; do not trust checkmarks by themselves.
3. Identify:
   - missing requirements;
   - partially implemented requirements;
   - deviations that introduce risk;
   - deliberate deviations that are safe and justified;
   - implementation added outside the approved scope.
   - approved intentional breaks incorrectly retained behind compatibility machinery;
   - unexplained preservation or surface growth not supported by the contract classification.
4. Do not report harmless naming or mechanical differences as defects. Put non-defective deviations in the conformance summary.
5. A plan-conformant implementation can still receive blocking findings.

### Step 6: Validate Bifrost skill-authoring documentation impact

Review the actual diff for changes a Bifrost skill author needs to know about. Do not rely only on the plan's conclusion or changed directory names.

Consider changes to:

- manifest syntax and validation;
- model selection and configuration;
- defaults and compatibility behavior;
- mappings and capability visibility;
- execution and planning semantics;
- evidence contracts;
- input/output contracts;
- RBAC;
- attachments and virtual files;
- limits and quotas;
- traces, debugging, and testing guidance.

When impact exists:

1. Read `ai/skill-authoring/README.md`, its relevant routed topic documents, and `ai/skill-authoring/source-verification.md`.
2. Verify guidance changed in the same implementation and accurately distinguishes enforced behavior, recommendations, and known limitations.
3. Verify every material documentation claim against focused tests, fixtures, samples, or production source using the evidence order in the README.
4. Apply the `LLM-First Authoring Standard`: applicability, normative force, terminology, supporting evidence, uncertainty, and routing must be clear without narrative filler.
5. Verify the README coverage table changed when topic coverage or confidence changed.
6. Treat missing, stale, unsupported, conflicting, or non-actionable guidance as a normal code-review finding with an appropriate severity.

If there is no skill-authoring impact, record the concrete rationale and evidence reviewed.

## Candidate-finding verification

Before reporting any finding:

1. Re-read the exact changed lines and surrounding implementation.
2. Trace the concrete trigger through callers and dependencies.
3. Search for an existing safeguard, validation, or compensating behavior.
4. Check relevant tests and framework/library semantics.
5. Confirm the issue is introduced by, worsened by, or directly relevant to the reviewed change.
6. State the observable impact without exaggeration.
7. For compatibility candidates, confirm the surface classification and supporting evidence before deciding whether the change is a protected-contract regression, an approved break, or an unjustified shim.

Do not report:

- purely stylistic preferences;
- hypothetical problems with no reachable trigger;
- issues already prevented by code you did not initially notice;
- broad architectural wishes unrelated to the change;
- pre-existing defects unless the change makes them materially worse or relies on them. Mention important pre-existing observations separately, not as change-blocking findings.

## Finding priorities

Use these priorities consistently:

- **P0 — Critical:** Catastrophic and broadly exploitable/release-blocking issue, such as unavoidable data loss, severe security compromise, or system-wide outage.
- **P1 — High:** A likely or high-impact correctness, security, compatibility, or reliability defect that should block merge.
- **P2 — Medium:** A real defect or meaningful regression with a narrower trigger or practical workaround; normally fixed before merge.
- **P3 — Low:** A concrete low-risk defect, missing defensive behavior, or maintainability issue worth addressing; may be follow-up work.

Do not inflate severity. Severity reflects impact, reachability, and likelihood—not how much code would be needed to fix it.

## Finding format

Each finding must be independently understandable and actionable:

```markdown
### [P1] Short imperative title

- **Location:** `path/to/file.ext:line`
- **Evidence:** What the changed code does and the relevant execution path.
- **Trigger:** The concrete input, configuration, state, concurrency condition, or failure mode.
- **Impact:** What becomes incorrect, insecure, incompatible, unavailable, or operationally misleading.
- **Recommendation:** The smallest appropriate correction or design constraint.
```

Keep line ranges tight. If several locations contribute to one defect, choose the most useful primary location and cite the others in the evidence.

## Output format

Lead with findings. Do not lead with a summary of work performed.

```markdown
## Code Review Findings

### [P1] ...
[Finding]

### [P2] ...
[Finding]

## Open Questions and Assumptions

- Questions that materially affect correctness or review confidence.

## Verification Results

- PASS — `<exact command>`
- FAIL — `<exact command>`: concise failure and attribution
- NOT RUN — `<command or check>`: reason and residual risk

## Requirements and Plan Conformance

- Implemented: [criteria with evidence]
- Partial: [criteria and missing portion]
- Missing: [criteria]
- Safe deviations: [non-defective differences and rationale]
- Contract classification: [affected canonical categories, evidence, approved breaks, protected consumers, public-surface delta, and shim/no-shim conformance]

## Skill-Authoring Documentation Impact

- **Assessment:** Affected / No impact
- **Rationale:** Concrete author-facing behavior examined
- **Documents reviewed:** Paths or `None`
- **Evidence checked:** Tests, fixtures, samples, and/or source
- **Coverage table:** Current / Update required / Not applicable
- **LLM-first usability:** Pass / Needs changes / Not applicable

## Residual Risks and Manual Verification

- Tests or environments unavailable during review
- Manual scenarios still required
- Compatibility assumptions not executable locally

## Disposition

- **Request changes** — one or more blocking findings
- **Approve with follow-ups** — no blocking findings; listed P3 or residual work remains
- **Approve** — no actionable findings and verification is sufficient
```

If there are no findings, say **“No actionable findings.”** Do not invent low-value comments to fill the report. Still provide verification results, residual risks, documentation impact, conformance, and disposition.

## Final review checklist

- [ ] Exact diff scope, base, staged, unstaged, and untracked files were identified.
- [ ] Changed behavior was traced beyond isolated diff hunks.
- [ ] Independent defect review happened before plan comparison.
- [ ] Applicable correctness, compatibility, security, concurrency, lifecycle, persistence, integration, performance, observability, and documentation risks were considered.
- [ ] Framework surfaces were classified before compatibility findings were evaluated, and approved breaks were distinguished from regressions of protected contracts.
- [ ] Unjustified compatibility machinery, retained obsolete behavior, accidental public types, leaked internal signature types, and unclassified `@ConditionalOnMissingBean` extension points were checked.
- [ ] Every reported finding has a reachable trigger, concrete impact, tight location, and supporting evidence.
- [ ] Candidate findings were checked for existing safeguards and false positives.
- [ ] Tests were assessed for quality and coverage, not merely counted.
- [ ] Commands reported as passing were actually run in this fresh review context.
- [ ] Ticket, plan, and testing-plan criteria were mapped to evidence when present.
- [ ] Skill-authoring documentation impact was assessed independently against the actual diff.
- [ ] Secrets and sensitive data were checked across logs, traces, metrics, errors, and object rendering where applicable.
- [ ] Findings are ordered by severity and the disposition matches them.

Remember: the goal is not to prove that the implementation followed its plan. The goal is to determine whether the change is safe and correct to merge, and to explain any reason it is not with precise, reproducible evidence.
