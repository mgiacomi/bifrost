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

2. **Locate existing tests and patterns**:
   - Find existing tests for the impacted modules
   - Find similar tests in adjacent features
   - Identify conventions for fixtures, builders, and test naming

### Step 2: Define Scope and Risks

Create a short, explicit list of:
- **Behaviors changing** (user-visible and internal)
- **Impacted areas** (files/components)
- **Primary risks** (regressions, edge cases, integrations)

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

### Step 5: Running Tests + Exit Criteria

Define:
- **Commands to run locally** (build + tests)
- **Any required profiles/env vars/test data**
- **Exit criteria** (what must be true to consider the change verified)

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

## How to Run
- [Build command]
- [Test command(s)]

## Exit Criteria
- [ ] Failing test exists and fails pre-fix (when applicable)
- [ ] All tests pass post-fix
- [ ] New/updated tests cover the changed behavior and key edge cases
- [ ] Manual verification steps (if any) are complete
```
