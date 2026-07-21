---
description: Create a testing plan to identify impacted areas, design tests (including failing tests), and define exit criteria
---

# Testing Plan

You are tasked with creating a focused testing plan for a code change. The goal is to identify what could break, design the right level of automated tests (unit/integration/e2e), and define clear exit criteria.

This command is specifically for planning tests (including creating a failing test that proves the problem) before implementation.

## Initial Response

When this command is invoked:

1. **Check if parameters were provided**:
   - If a file path to a plan, ticket, or research doc was provided, immediately read any provided files FULLY

2. **If no parameters provided**, respond with:
```
I'll help you create a testing plan for this change.

Please provide:
1. The implementation plan path (preferred) or a short description of the change
2. Any ticket/requirements/research docs (paths) that define expected behavior
3. How you intend to verify the change (manual steps, environments, constraints)

I'll map impacted areas, propose a failing test (if applicable), and outline the minimum set of automated tests to add/update.
```

Then wait for the user's input.

## Process Steps

### Step 1: Gather Context

1. **Read all mentioned files immediately and FULLY**:
   - Implementation plan document(s)
   - Ticket/requirements files
   - Research documents
   - Any logs/error output (if provided)
   - If the implementation plan marks `Skill-Authoring Documentation Impact` as `Affected`, read the relevant `ai/skill-authoring/` documents identified by the plan
   - For framework work, read the implementation plan's `Contract and Compatibility Impact` section and use its classifications to determine compatibility test scope

2. **Locate existing tests and patterns**:
   - Find existing tests for the impacted modules
   - Find similar tests in adjacent features
   - Identify conventions for fixtures, builders, and test naming

### Step 2: Define Scope and Risks

Create a short, explicit list of:
- **Behaviors changing** (user-visible and internal)
- **Impacted areas** (files/components)
- **Primary risks** (regressions, edge cases, integrations)
- **Authoring claims requiring evidence** when the change affects `ai/skill-authoring/` guidance
- **Protected compatibility paths** and their evidence, plus **intentionally removed obsolete paths**

Tests establish existing behavior but do not independently establish a supported compatibility promise. Use the canonical categories from `ai/thoughts/framework-feature-design-lens.md`: Application API, Supported SPI, Configuration and manifest contracts, Persisted or serialized contracts, Ephemeral diagnostic formats, and Internal or accidentally exposed implementation.

When the work affects the Bifrost Console application-adapter REST/SSE, acquisition, problem, or consumed NDJSON boundary, carry forward the approved Java-to-Go boundary-coordination scope and cover the affected executable fixtures, exact release-string rejection, and observable semantics.

### Step 3: Plan the Failing Test (When Applicable)

When a bug or incorrect behavior is involved, propose a **minimal failing test** that:
- Fails reliably on the current behavior
- Demonstrates the problem clearly
- Is as low-cost as possible (unit first, then integration if necessary)

If the work is a pure refactor with no behavior change, explicitly say so and focus on regression coverage.

### Step 4: Specify Tests to Add/Update

For each proposed test, specify:
- **Name**
- **Type**: unit / integration / e2e
- **Location**: path where it should live
- **What it proves**: exact expected behavior
- **Inputs/fixtures needed**
- **Mocking strategy** (if any)

Preserve compatibility-path tests only for deliberately protected Application API, Supported SPI, Configuration and manifest contracts, or Persisted or serialized contracts. Update or remove tests that encode an approved obsolete Internal or accidentally exposed implementation; do not require old and new behavior simultaneously after an approved break. When Application API, Supported SPI, or accidental public exposure changes, add boundary tests for public signatures, leaked internal types, and unintended extension points where applicable.

For Ephemeral diagnostic formats, test current writer/reader/projector/debugging-tool coherence, diagnostic usefulness, accuracy, ordering, failure visibility, security boundaries, and redaction. Do not require historical trace readability, old schemas, or obsolete fixtures.

When skill-authoring documentation is affected, ensure the proposed focused tests establish the author-facing semantics the updated guidance will describe. Do not add tests merely to exercise prose; test the underlying framework behavior.

### Step 5: Running Tests + Exit Criteria

Define:
- **Commands to run locally** (build + tests)
- **Any required profiles/env vars/test data**
- **Exit criteria** (what must be true to consider the change verified)
- For framework changes, confirmation that protected paths still work and approved obsolete paths are absent rather than retained behind fallbacks

## Output Artifact

Write the testing plan to:
- `ai/thoughts/plans/YYYY-MM-DD-ENG-XXXX-description-testing.md`

If there is no ticket number, omit it.

## Testing Plan Template

Use this structure:

```markdown
# [Feature/Task Name] Testing Plan

## Change Summary
- [What is changing]

## Impacted Areas
- [File/component]

## Risk Assessment
- [High-risk behaviors]
- [Edge cases]
- [Protected compatibility paths and intentionally removed obsolete paths]

## Existing Test Coverage
- [Relevant existing tests]
- [Gaps]

## Bug Reproduction / Failing Test First
- Type: unit/integration/e2e
- Location:
- Arrange/Act/Assert outline:
- Expected failure (pre-fix):

## Tests to Add/Update
### 1) [Test Name]
- Type:
- Location:
- What it proves:
- Fixtures/data:
- Mocks:
- Contract classification: [one canonical category]
- Compatibility expectation: [protected path / approved removal / current-run diagnostic coherence]

## How to Run
- [Build command]
- [Test command(s)]

## Exit Criteria
- [ ] Failing test exists and fails pre-fix (when applicable)
- [ ] All tests pass post-fix
- [ ] New/updated tests cover the changed behavior and key edge cases
- [ ] Tests cited as evidence for changed skill-authoring guidance establish the documented behavior (when applicable)
- [ ] Protected compatibility paths pass, and approved obsolete paths are removed without simultaneous old/new behavior (when applicable)
- [ ] Changed public boundaries and current-run trace obligations are covered according to the plan's classification (when applicable)
- [ ] Manual verification steps (if any) are complete
```
