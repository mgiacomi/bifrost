---
description: Create detailed implementation plans through interactive research and iteration
---

# Implementation Plan

You are tasked with creating detailed implementation plans through an interactive, iterative process. You should be skeptical, thorough, and work collaboratively with the user to produce high-quality technical specifications.

## Configuration (defaults you can adjust per repo)

- **Planning docs directory**: `ai/thoughts/plans/`
- **Research docs directory**: `ai/thoughts/research/`
- **Ticket/requirements directory** (if present): `ai/thoughts/tickets/`
- **Testing plan command**: `3_testing_plan.md` (creates a dedicated test plan artifact)
- **Skill-authoring knowledge base**: `ai/skill-authoring/` (must stay synchronized with author-facing framework behavior)

## Initial Response

When this command is invoked:

1. **Check if parameters were provided**:
   - If a file path or ticket reference was provided as a parameter, skip the default message
   - Immediately read any provided files FULLY
   - Begin the research process

2. **If no parameters provided**, respond with:
```
I'll help you create a detailed implementation plan. Let me start by understanding what we're building.

Please provide:
1. The task/ticket description (or reference to a ticket file)
2. Any relevant context, constraints, or specific requirements
3. Links to related research or previous implementations

I'll analyze this information and work with you to create a comprehensive plan.

```

Then wait for the user's input.

## Process Steps

### Step 1: Context Gathering & Initial Analysis

1. **Read all mentioned files immediately and FULLY**:
   - Ticket/requirements files (e.g., `ai/thoughts/tickets/eng_XXXX.md`)
   - Research documents (e.g., `ai/thoughts/research/2025-01-08-ENG-XXXX-description.md`)
   - Related implementation plans
   - Any JSON/data files mentioned
   - **IMPORTANT**: Use your file-reading tool WITHOUT limit/offset parameters to read entire files
   - **CRITICAL**: DO NOT start broader research steps before reading these files yourself in the main context
   - **NEVER** read files partially - if a file is mentioned, read it completely
   - For Bifrost framework work, read `ai/thoughts/framework-feature-design-lens.md` completely before forming compatibility conclusions

2. **Read all files identified by research steps**:
   - After research steps complete, read ALL files they identified as relevant
   - Read them FULLY into the main context
   - This ensures you have complete understanding before proceeding

3. **Analyze and verify understanding**:
   - Cross-reference the ticket requirements with actual code
   - Identify any discrepancies or misunderstandings
   - Note assumptions that need verification
   - Determine true scope based on codebase reality
   - For framework work, inventory affected surfaces and evidence, then classify each as Application API, Supported SPI, Configuration and manifest contracts, Persisted or serialized contracts, Ephemeral diagnostic formats, or Internal or accidentally exposed implementation before evaluating compatibility

4. **Present informed understanding and focused questions**:
   ```
   Based on the ticket and my research of the codebase, I understand we need to [accurate summary].

   I've found that:
   - [Current implementation detail with file:line reference]
   - [Relevant pattern or constraint discovered]
   - [Potential complexity or edge case identified]

   Questions that my research couldn't answer:
   - [Specific technical question that requires human judgment]
   - [Business logic clarification]
   - [Design preference that affects implementation]
   ```

   Only ask questions that you genuinely cannot answer through code investigation.

### Step 2: Research & Discovery

After getting initial clarifications:

1. **If the user corrects any misunderstanding**:
   - DO NOT just accept the correction
   - Run new research steps to verify the correct information
   - Read the specific files/directories they mention
   - Only proceed once you've verified the facts yourself

2. **Create a research todo list** using whatever todo/task-tracking capability is available to you

3. **Run parallel research steps for comprehensive discovery (when supported)**:
   - Break the investigation into focused, independent tracks and run them concurrently when possible
   - Example tracks:
     - **Codebase locator**: find the primary entry points and file ownership for the feature
     - **Implementation tracer**: trace data flow and call paths across layers
     - **Pattern finder**: find similar existing features to model the approach after
     - **Tests/examples finder**: locate existing tests, fixtures, and usage examples
     - **Notes/history**: search `ai/thoughts/` for prior research, plans, or decisions
   - Always request/record concrete `file:line` references for findings

4. **Wait for ALL research steps to complete** before proceeding

5. **Present findings and design options**:
   ```
   Based on my research, here's what I found:

   **Current State:**
   - [Key discovery about existing code]
   - [Pattern or convention to follow]

   **Design Options:**
   1. [Option A] - [pros/cons]
   2. [Option B] - [pros/cons]

   **Open Questions:**
   - [Technical uncertainty]
   - [Design decision needed]

   Which approach aligns best with your vision?
   ```

   For framework options, compare the protected consumers, intended breaks, atomic repository updates, public-surface delta, and shim/no-shim decision. A public modifier, interface, constructor, Spring bean, `@ConditionalOnMissingBean`, existing test, or previous implementation does not by itself establish a supported contract. Use the canonical policy in the design lens rather than inferring protection from technical exposure.

6. **Assess skill-authoring documentation impact**:
   - Determine whether the proposed work changes anything a Bifrost skill author needs to know, including manifest syntax or validation, defaults, mappings, execution or planning semantics, evidence, input/output contracts, capability visibility or RBAC, attachments or virtual files, model selection, limits or quotas, traces, debugging, or testing guidance
   - Do not decide this from changed directory names alone; trace the behavior to its author-facing effects
   - Read `ai/skill-authoring/README.md` and use its routing guidance to read only the relevant topic documents. Follow `ai/skill-authoring/source-verification.md` when source-level verification is required
   - Identify the focused tests, fixtures, samples, and production code that will support any new or changed guidance
   - Decide whether the README coverage table must change because a topic is added or its coverage/confidence changes
   - Plan documentation changes to satisfy the README's `LLM-First Authoring Standard`; do not plan narrative or duplicated prose that does not improve retrieval, interpretation, or task execution
   - Carry the result into the mandatory `Skill-Authoring Documentation Impact` section of the final plan. An omitted assessment is not equivalent to "No impact"

7. **Plan testing before implementation**:
   - After the approach is agreed, recommend running `3_testing_plan.md` before implementation
   - The testing plan should outline impacted areas, a failing test (when applicable), and exit criteria

### Step 3: Plan Structure Development

Once aligned on approach:

1. **Create initial plan outline**:
   ```
   Here's my proposed plan structure:

   ## Overview
   [1-2 sentence summary]

   ## Implementation Phases:
   1. [Phase name] - [what it accomplishes]
   2. [Phase name] - [what it accomplishes]
   3. [Phase name] - [what it accomplishes]

   Does this phasing make sense? Should I adjust the order or granularity?
   ```

2. **Get feedback on structure** before writing details

### Step 4: Detailed Plan Writing

After structure approval:

1. **Write the plan** to `ai/thoughts/plans/YYYY-MM-DD-ENG-XXXX-description.md`
   - Format: `YYYY-MM-DD-ENG-XXXX-description.md` where:
     - YYYY-MM-DD is today's date
     - ENG-XXXX is the ticket number (omit if no ticket)
     - description is a brief kebab-case description
   - Examples:
     - With ticket: `2025-01-08-ENG-1478-parent-child-tracking.md`
     - Without ticket: `2025-01-08-improve-error-handling.md`
2. **Use this template structure**:

```markdown
# [Feature/Task Name] Implementation Plan

## Overview

[Brief description of what we're implementing and why]

## Current State Analysis

[What exists now, what's missing, key constraints discovered]

## Desired End State

[A Specification of the desired end state after this plan is complete, and how to verify it]

### Key Discoveries:
- [Important finding with file:line reference]
- [Pattern to follow]
- [Constraint to work within]

## What We're NOT Doing

[Explicitly list out-of-scope items to prevent scope creep]

## Skill-Authoring Documentation Impact

**Impact**: [Affected / No impact]

- **Rationale**: [Explain which author-facing behavior changes, or why the change is purely internal and does not alter authoring guidance]
- **Documents to update**: [`ai/skill-authoring/...`, or `None`]
- **Supporting evidence**: [Focused tests, fixtures, samples, and/or production source that establish the documented behavior]
- **Coverage table update**: [Required / Not required, with rationale]
- **LLM-first usability**: [How routing, topic boundaries, self-contained guidance, terminology, and explicit limitations will remain clear; or `Not applicable` when there is no impact]

If the impact is `Affected`, include each documentation change in the appropriate implementation phase below. Do not defer it to an unspecified follow-up. If existing documentation conflicts with executable behavior, call out the discrepancy explicitly rather than silently choosing one.

## Contract and Compatibility Impact

Required for Bifrost framework work. Cover every category, including categories with no impact:

| Surface | Classification and supporting evidence | Planned compatibility treatment |
| --- | --- | --- |
| Application API | [Affected entry points, evidence, and protected consumers, or no impact] | [Preserve or intentional break] |
| Supported SPI | [Affected extension points, evidence, and protected consumers, or no impact] | [Preserve or intentional break] |
| Configuration and manifest contracts | [Affected documented behavior and developer/skill-author impact, or no impact] | [Preserve or explicit atomic break] |
| Persisted or serialized contracts | [Durable intent and evidence, or no impact] | [Preserve or intentional break] |
| Ephemeral diagnostic formats | [Current-run trace impact, or no impact] | [Current-version coherence and security treatment] |
| Internal or accidentally exposed implementation | [Affected surfaces and technical exposure, or no impact] | [Atomic removal/update or justified preservation] |

- **Evidence of supported contracts**: [Documentation, explicit API/SPI allowlist, approved ticket, and/or verified consumer usage]
- **Intended breaks**: [Each approved break and its impact, or none]
- **In-repository consumers to update**: [Callers, tests, samples, fixtures, configuration, manifests, and documentation]
- **Public-surface delta**: [Types, signatures, constructors, and Spring extension points added or removed]
- **Shim decision**: **[Shim / No shim].** [For a shim, identify the protected contract, explain why atomic change is inappropriate, name the mechanism, and state its removal condition]
- **Java-to-Go boundary coordination**: **[Not applicable / Not required / Required].** [When the application-adapter REST/SSE, acquisition, problem, or consumed NDJSON boundary is affected, identify the synchronized Java, Go, fixture, test, and documentation changes that must ship together.]

## Implementation Approach

[High-level strategy and reasoning]

## Phase 1: [Descriptive Name]

### Overview
[What this phase accomplishes]

### Changes Required:

#### 1. [Component/File Group]
**File**: `path/to/file.ext`
**Changes**: [Summary of changes]

```[language]
// Specific code to add/modify
```

### Success Criteria:

#### Automated Verification:
- [ ] Project build/check passes: `[project build/check command]`
- [ ] Unit tests pass: `[unit test command]`
- [ ] Linting/formatting passes: `[lint/format command]`
- [ ] Integration tests (if any) pass: `[integration test command]`
- [ ] Skill-authoring guidance changed in this phase is supported by the cited tests, fixtures, samples, or production source (when applicable)
- [ ] Relevant `ai/skill-authoring/` documents and the README coverage table are updated (when applicable)
- [ ] Changed skill-authoring guidance satisfies the README's `LLM-First Authoring Standard` (when applicable)

#### Manual Verification:
- [ ] Feature works as expected when tested via UI
- [ ] Performance is acceptable under load
- [ ] Edge case handling verified manually
- [ ] No regressions in related features

---

## Phase 2: [Descriptive Name]

[Similar structure with both automated and manual success criteria...]

---

## Testing Strategy

### Unit Tests:
- [What to test]
- [Key edge cases]

### Integration Tests:
- [End-to-end scenarios]

 **Note**: Prefer a dedicated testing plan artifact created via `3_testing_plan.md` for full details (impacted areas, failing test first, commands to run, exit criteria). Keep this section as a high-level summary.

### Manual Testing Steps:
1. [Specific step to verify feature]
2. [Another verification step]
3. [Edge case to test manually]

## Performance Considerations

[Any performance implications or optimizations needed]

## Migration Notes

[If applicable, how to handle existing data/systems]

## References

- Original ticket/requirements: `ai/thoughts/tickets/eng_XXXX.md` (or wherever the repo stores tickets)
- Related research: `ai/thoughts/research/[relevant].md`
- Similar implementation: `[file:line]`
```

### Step 5: Sync and Review

1. **Present the draft plan location**:
   ```
   I've created the initial implementation plan at:
   `ai/thoughts/plans/YYYY-MM-DD-ENG-XXXX-description.md`

   Please review it and let me know:
   - Are the phases properly scoped?
   - Are the success criteria specific enough?
   - Any technical details that need adjustment?
   - Missing edge cases or considerations?
   ```

2. **Iterate based on feedback** - be ready to:
   - Add missing phases
   - Adjust technical approach
   - Clarify success criteria (both automated and manual)
   - Add/remove scope items

3. **Continue refining** until the user is satisfied

## Important Guidelines

1. **Be Skeptical**:
   - Question vague requirements
   - Identify potential issues early
   - Ask "why" and "what about"
   - Don't assume - verify with code

2. **Be Interactive**:
   - Don't write the full plan in one shot
   - Get buy-in at each major step
   - Allow course corrections
   - Work collaboratively

3. **Be Thorough**:
   - Read all context files COMPLETELY before planning
   - Research actual code patterns using parallel sub-tasks
   - Include specific file paths and line numbers
   - Write measurable success criteria with clear automated vs manual distinction
   - Include an explicit, evidence-backed skill-authoring documentation impact assessment in every final plan
   - Prefer repo-standard wrapper commands (e.g., `make`, `mvn`, `gradle`, `npm`, `just`, etc.) over ad-hoc multi-step commands

4. **Be Practical**:
   - Focus on incremental, testable changes
   - Consider migration and rollback
   - Think about edge cases
   - Include "what we're NOT doing"

5. **Track Progress**:
   - Track planning tasks using whatever todo/task-tracking capability is available
   - Update todos as you complete research
   - Mark planning tasks complete when done

6. **No Open Questions in Final Plan**:
   - If you encounter open questions during planning, STOP
   - Research or ask for clarification immediately
   - Do NOT write the plan with unresolved questions
   - The implementation plan must be complete and actionable
   - Every decision must be made before finalizing the plan
   - Do not leave skill-authoring documentation impact unresolved or use "update docs if needed" as a placeholder
   - For framework work, do not finalize with an unresolved contract classification, an unclassified documented contract, vague migration placeholders, or unexplained compatibility machinery such as overloads, aliases, fallbacks, adapters, deprecated paths, legacy readers, duplicate interfaces, or dual behavior

7. **Stop After Plan Creation (Do Not Implement)**:
   - This command's scope is strictly limited to research and creating the implementation plan document.
   - Once the implementation plan is created/written and approved (even if automatically approved by IDE policies), you must STOP and end your turn.
   - Do NOT write source code changes or begin any execution tasks until the user issues a separate prompt explicitly directing you to begin implementation.

## Success Criteria Guidelines

**Always separate success criteria into two categories:**

1. **Automated Verification** (can be run automatically):
   - Commands that can be run: `make test`, `npm run lint`, etc.
   - Specific files that should exist
   - Code compilation/type checking
   - Automated test suites

2. **Manual Verification** (requires human testing):
   - UI/UX functionality
   - Performance under real conditions
   - Edge cases that are hard to automate
   - User acceptance criteria

**Format example:**
```markdown
### Success Criteria:

#### Automated Verification:
- [ ] Database migration runs successfully: `make migrate`
- [ ] All unit tests pass: `go test ./...`
- [ ] No linting errors: `golangci-lint run`
- [ ] API endpoint returns 200: `curl localhost:8080/api/new-endpoint`

#### Manual Verification:
- [ ] New feature appears correctly in the UI
- [ ] Performance is acceptable with 1000+ items
- [ ] Error messages are user-friendly
- [ ] Feature works correctly on mobile devices
```

## Common Patterns

### For Database Changes:
- Start with schema/migration
- Add store methods
- Update business logic
- Expose via API
- Update clients

### For New Features:
- Research existing patterns first
- Start with data model
- Build backend logic
- Add API endpoints
- Implement UI last

### For Refactoring:
- Document current behavior
- Plan incremental changes
- Classify affected framework surfaces before making compatibility decisions
- Preserve deliberately protected contracts; for approved breaks, remove obsolete behavior and update in-repository consumers atomically
- Include a concrete migration strategy only when a protected contract or explicit ticket requires one; otherwise record the no-shim decision

## Sub-task Spawning Best Practices

When spawning research sub-tasks:

1. **Spawn multiple tasks in parallel** for efficiency
2. **Each task should be focused** on a specific area
3. **Provide detailed instructions** including:
   - Exactly what to search for
   - Which directories to focus on
   - What information to extract
   - Expected output format
4. **Be EXTREMELY specific about directories**:
   - Use concrete repo paths (packages/modules) rather than vague labels like "backend" or "UI"
   - Include the full path context in your prompts
5. **Specify read-only tools** to use
6. **Request specific file:line references** in responses
7. **Wait for all tasks to complete** before synthesizing
8. **Verify sub-task results**:
   - If a sub-task returns unexpected results, spawn follow-up tasks
   - Cross-check findings against the actual codebase
   - Don't accept results that seem incorrect

Example of spawning multiple tasks:
Run multiple independent research steps in parallel when possible, then synthesize results once all have completed.

## Example Interaction Flow

```
User: /create_plan
Assistant: I'll help you create a detailed implementation plan...

User: We need to implement [feature]. See ai/thoughts/tickets/eng_XXXX.md
Assistant: Let me read that ticket/requirements file completely first...

[Reads file fully]

Based on the ticket, I understand we need to [accurate summary tied to codebase reality]. Before I start planning, I have some questions...

[Interactive process continues...]
```
