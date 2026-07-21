---
description: Implement technical plans from ai/thoughts/plans with verification
---

# Implement Plan

You are tasked with implementing an approved technical plan from `ai/thoughts/plans/`. These plans contain phases with specific changes and success criteria.

## Getting Started

When given a plan path:
- Read the plan completely and check for any existing checkmarks (- [x])
- Read the original ticket and all files mentioned in the plan
- If a testing plan exists for this work (for example, `ai/thoughts/plans/*-testing.md`), read it completely and follow it during implementation
- For Bifrost framework work, read and follow the plan's `Contract and Compatibility Impact` section and the canonical policy in `ai/thoughts/framework-feature-design-lens.md` before editing code
- When the plan affects the Bifrost Console application-adapter REST/SSE boundary, also read `ai/thoughts/bifrost-console-compatibility.md` and treat the plan's console compatibility decision as an implementation constraint
- Read the plan's `Skill-Authoring Documentation Impact` section. If it is missing (for example, in an older plan), perform the assessment described below before implementation
- When skill-authoring impact is `Affected`, read `ai/skill-authoring/README.md`, the relevant routed topic documents, and `ai/skill-authoring/source-verification.md` before editing the knowledge base
- **Read files fully when practical** - avoid partial reads unless a file is very large; if you must read in chunks, capture sufficient surrounding context
- Think deeply about how the pieces fit together
- Create a todo list to track your progress
- Start implementing if you understand what needs to be done

If no plan path provided, ask for one.

## Implementation Philosophy

Plans are carefully designed, but reality can be messy. Your job is to:
- Follow the plan's intent while adapting to what you find
- Implement each phase fully before moving to the next
- Verify your work makes sense in the broader codebase context
- Update checkboxes in the plan as you complete sections

For framework work, treat the plan's classification of Application API, Supported SPI, Configuration and manifest contracts, Persisted or serialized contracts, Ephemeral diagnostic formats, and Internal or accidentally exposed implementation as an implementation constraint. A public modifier, interface, constructor, Spring bean, `@ConditionalOnMissingBean`, existing test, or previous implementation does not by itself establish a supported contract. Do not add unplanned overloads, aliases, fallbacks, adapters, deprecated paths, legacy readers, duplicate interfaces, compatibility constructors, bridge types, or dual behavior.

When a break is approved, remove intentionally obsolete paths completely and atomically update every in-repository caller, test, sample, fixture, configuration, manifest, and documentation reference identified by the plan. If implementation discovers a documented but unclassified contract, a verified protected consumer, or a need for compatibility machinery, treat that as a plan mismatch and use the approval flow below instead of silently adding a shim.

When things don't match the plan exactly, think about why and communicate clearly. The plan is your guide, but your judgment matters too.

If you encounter a mismatch:
- STOP and think deeply about why the plan can't be followed
- If plan needs to change, propose a minimal plan update + rationale and get approval before proceeding.
- Present the issue clearly:
  ```
  Issue in Phase [N]:
  Expected: [what the plan says]
  Found: [actual situation]
  Why this matters: [explanation]

  How should I proceed?
  ```

## Keep the Skill-Authoring Knowledge Base Current

The plan's documentation assessment is a starting point, not a permanent conclusion. Reassess it against the behavior actually implemented and the final diff.

A change has skill-authoring impact when it changes anything a Bifrost skill author needs to know, including manifest syntax or validation, defaults, mappings, execution or planning semantics, evidence, input/output contracts, capability visibility or RBAC, attachments or virtual files, model selection, limits or quotas, traces, debugging, or testing guidance. Do not infer impact solely from file paths, and do not update the knowledge base for a purely internal refactor that leaves author-facing behavior unchanged.

When impact is present:
- Update the relevant `ai/skill-authoring/` documents in the same phase as the behavior change; do not defer them to an unspecified follow-up
- Support guidance using the evidence order in `ai/skill-authoring/README.md`: knowledge base, focused tests, fixtures, samples, then production source for exact behavior and edge cases
- Distinguish enforced behavior from recommendations and known limitations
- Follow the README's `LLM-First Authoring Standard`: preserve progressive disclosure and routing, keep topic guidance self-contained and precise, and remove filler or duplication that does not improve retrieval, interpretation, or task execution
- Update `ai/skill-authoring/README.md` coverage when a topic is added or its coverage/confidence changes
- Report any conflict between the knowledge base and executable behavior explicitly; do not silently choose one

If actual implementation reveals skill-authoring impact that the approved plan marked `No impact`, or materially changes the planned documentation scope, treat that as a plan mismatch. Propose the minimal plan update and rationale using the mismatch process above before proceeding.

If implementation reveals that the approved `consoleCompatibilityVersion` increment or no-increment decision is incorrect, treat that as a plan mismatch. Do not silently change the version or preserve it. When an approved increment is implemented, update the Java and Go constants, protocol fixtures, rejection tests, and documentation atomically.

## Verification Approach

After implementing a phase:
- Run the success criteria checks (usually `mvn test` covers everything)
- Fix any issues before proceeding
- Review the phase's actual changes for author-facing semantic impact and confirm the plan's documentation assessment is still correct
- When documentation changed, verify its claims against the cited tests, fixtures, samples, or production source; apply the README's LLM-first acceptance questions; and update routing and coverage when required
- Update your progress in both the plan and your todos
- Check off completed items in the plan file itself by updating the checkboxes in the plan
- For framework work, verify the phase introduced no accidental public types, leaked internal types in public Application API or Supported SPI signatures, or new or retained `@ConditionalOnMissingBean` beans not deliberately classified as Supported SPI
  ```
  Automated verification passed:
  - [List automated checks that passed]

  Please perform the manual verification steps listed in the plan:
  - [List manual verification items from the plan]

  ```

## If You Get Stuck

When something isn't working as expected:
- First, make sure you've read and understood all the relevant code
- Consider if the codebase has evolved since the plan was written
- Present the mismatch clearly and ask for guidance

Use sub-agents or deep research steps sparingly - mainly for targeted debugging or exploring unfamiliar territory.

## Resuming Work

If the plan has existing checkmarks:
- Trust that completed work is done
- Pick up from the first unchecked item
- Verify previous work only if something seems off
- Still reassess skill-authoring documentation impact for the final combined diff before declaring the plan complete

Before final completion of framework work, repeat the public-surface and Spring-extension-point checks across the full diff, and confirm approved obsolete paths were removed rather than retained behind compatibility machinery.

Remember: You're implementing a solution, not just checking boxes. Keep the end goal in mind and maintain forward momentum.
