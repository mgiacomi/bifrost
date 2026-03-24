---
description: Validate implementation against plan, verify success criteria, identify issues
---

# Validate Plan

You are tasked with validating that an implementation plan was correctly executed, verifying all success criteria and identifying any deviations or issues.

## Initial Setup

When invoked:
1. **Determine context** - Are you in an existing conversation or starting fresh?
   - If existing: Review what was implemented in this session
   - If fresh: Need to discover what was done through GIT history and codebase analysis

2. **Locate the plan**:
   - If plan path provided, use it
   - Otherwise, it might already be in your context

3. **Gather implementation evidence**:
   ```bash
   # Review local working copy changes (uncommitted)
   git status
   git diff
   ```

## Validation Process

### Step 1: Context Discovery

If starting fresh or need more context:

1. **Read the implementation plan** completely
   - If a dedicated testing plan exists (for example, `ai/thoughts/plans/*.md`, but not `*-testing.md`), read it completely
2. **Read the testing plan if present**:
   - If a dedicated testing plan exists (for example, `ai/thoughts/plans/*-testing.md`), read it completely
   - Treat it as the source of truth for what tests should have been added/updated and what commands should have been run
3. **Identify what should have changed**:
   - List all files that should be modified
   - Note all success criteria (automated and manual)
   - Identify key functionality to verify

4. **Spawn parallel research tasks** to discover implementation:

   If your environment supports true parallel sub-tasks, use them. Otherwise, do these sequentially.

   ```
   Task 1 - Verify database changes:
   Research if migration [N] was added and schema changes match plan.
   Check: migration files, schema version, table structure
   Return: What was implemented vs what plan specified

   Task 2 - Verify code changes:
   Find all modified files related to [feature].
   Compare actual changes to plan specifications.
   Return: File-by-file comparison of planned vs actual

   Task 3 - Verify test coverage:
   Check if tests were added/modified as specified.
   If a testing plan exists, verify that the planned tests were implemented.
   Run test commands and capture results.
   Return: Test status and any missing coverage

   Task 4 - Security Review:
   Check to see if any security considerations were mentioned in the plan.
   If so, verify that appropriate security measures were implemented.
   If this is a Spring Boot application, report on any changes made to security configuration, security annotations, or security-related UI code changes.
   Return: Security status and any missing coverage
   ```

### Step 2: Systematic Validation

For each phase in the plan:

1. **Check completion status**:
   - Look for checkmarks in the plan (- [x])
   - Verify the actual code matches claimed completion

2. **Run automated verification**:
   - Execute each command from "Automated Verification"
   - Document pass/fail status
   - If failures, investigate root cause

3. **Assess manual criteria**:
   - List what needs manual testing
   - Provide clear steps for user verification

4. **Think deeply about edge cases**:
   - Were error conditions handled?
   - Are there missing validations?
   - Could the implementation break existing functionality?

### Step 3: Generate Validation Report

Create comprehensive validation summary:

```markdown
## Validation Report: [Plan Name]

### Implementation Status
✓ Phase 1: [Name] - Fully implemented
✓ Phase 2: [Name] - Fully implemented
⚠️ Phase 3: [Name] - Partially implemented (see issues)

### Automated Verification Results
✓ Build passes: `maven compile`
✓ Tests pass: `maven test`
✓ Package passes: `maven package`

### Code Review Findings

#### Matches Plan:
- Database migration correctly adds [table]
- API endpoints implement specified methods
- Error handling follows plan

#### Deviations from Plan:
- Used different variable names in [file:line]
- Added extra validation in [file:line] (improvement)

#### Potential Issues:
- Missing index on foreign key could impact performance
- No rollback handling in migration

### Manual Testing Required:
1. UI functionality:
   - [ ] Verify [feature] appears correctly
   - [ ] Test error states with invalid input

2. Integration:
   - [ ] Confirm works with existing [component]
   - [ ] Check performance with large datasets if possible

## Working with Existing Context

If you were part of the implementation:
- Review the conversation history
- Check your todo list for what was completed
- Focus validation on work done in this session
- Be honest about any shortcuts or incomplete items

## Important Guidelines

1. **Be thorough but practical** - Focus on what matters
2. **Run all automated checks** - Don't skip verification commands
3. **Document everything** - Both successes and issues
4. **Think critically** - Question if the implementation truly solves the problem
5. **Consider maintenance** - Will this be maintainable long-term?

## Validation Checklist

Always verify:
- [ ] All phases marked complete are actually done
- [ ] Automated tests pass
- [ ] Testing plan (if present) was followed and planned tests exist
- [ ] Code follows existing patterns
- [ ] No regressions introduced
- [ ] Error handling is robust
- [ ] Documentation updated if needed
- [ ] Manual test steps are clear

Remember: Good validation catches issues before they reach production. Be constructive but thorough in identifying gaps or improvements.