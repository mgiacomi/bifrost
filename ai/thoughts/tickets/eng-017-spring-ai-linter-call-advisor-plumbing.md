# Ticket: eng-017-spring-ai-linter-call-advisor-plumbing.md
## Issue: Introduce Spring AI Advisor Wiring So YAML Skills Can Attach Runtime Linter Policies

### Why This Ticket Exists
Once linter configuration exists, Bifrost needs a runtime integration point that can intercept model generations without baking a bespoke orchestration loop into mission execution. Phase 4 points to Spring AI advisor APIs for that boundary, but the current chat client factory only sets provider-specific options and does not yet expose advisor composition.

This ticket adds the wiring needed to attach skill-specific advisor behavior to chat clients.

---

## Goal
Extend chat-client construction so Bifrost can register skill-specific Spring AI advisor instances, including a future linter advisor, when building a YAML skill execution client.

The main outcome should be:

- advisor composition is part of Bifrost chat-client creation
- runtime skill execution can attach linter behavior without rewriting `ExecutionCoordinator`
- advisor wiring remains provider-agnostic

---

## Non-Goals
This ticket should **not** introduce:

- the full linter retry algorithm itself
- new YAML schema work
- broad mission-engine refactors
- provider-specific retry logic outside Spring AI advisor hooks

---

## Required Outcomes

### Functional
- Add a way for Bifrost to resolve advisors for a specific YAML skill definition or execution configuration.
- Update chat-client creation to apply zero or more advisors during client construction.
- Ensure skills without linter config behave exactly as they do today.
- Keep provider-specific model/thinking option behavior intact while layering advisor support on top.

### Structural
- Advisor selection lives behind a dedicated abstraction rather than inline `ExecutionCoordinator` logic.
- Chat-client construction remains the one place where provider options and advisor attachments are composed.
- Runtime execution code does not need to know advisor internals.

### Testing
- Tests prove chat clients can be created with advisor attachments.
- Tests prove non-linter skills do not receive unnecessary advisor behavior.
- Tests prove existing provider option configuration remains intact after advisor support is added.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactory.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/...`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/chat/SpringAiSkillChatClientFactoryTests.java`

---

## Acceptance Criteria
- Bifrost can attach Spring AI advisors during YAML skill chat-client construction.
- Existing provider-specific option resolution remains unchanged for skills without advisors.
- The runtime is ready for a skill-scoped linter advisor without introducing a parallel retry framework.

---

## Definition of Done
This ticket is done when YAML skill execution supports provider-agnostic Spring AI advisor attachment through the normal chat-client factory path.
