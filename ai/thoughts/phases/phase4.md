# Phase 4 - Advanced Integrations (Security, Storage, Linter)

## Goal
Leverage advanced Spring integrations to harden the platform: secure discovery and execution with Spring Security, mature storage abstractions, and implement the full linter lifecycle with Spring AI advisory hooks.

## Primary Outcomes
- RBAC enforces which YAML skills or `@SkillMethod`s can be executed by the sub-agent based on the Principal.
- `BifrostSession` automatically isolates internal logs and payloads to standard Spring `FileSystemResource` or `S3Resource`.
- Linter validation loops automatically correct models.

## Scope
- Security context propagation.
- External VFS abstraction limit tracking.
- Linter manifest schema, boot validation, runtime enforcement, retry policy, and telemetry.
- Spring AI CallAdvisor implementations for self-correction.

## Detailed Tasks
### 1. Spring Security Bridge
- Implement an `AccessGuard` component that references `SecurityContextHolder.getContext().getAuthentication()`.
- Compare Principal authorities against the `rbac` arrays specified in the Private Manifest. Allow/Deny operations based on these rules.

### 2. VFS Data Isolation Behind Interfaces
- Create a stable `VirtualFileSystem` interface so that future iterations can swap out storage backends.
- For the MVP, implement this interface using `FileSystemResource` targeting the system `temp` directory.
- Automate prefixing these resources with the current `sessionId`.

### 3. Linter as a CallAdvisor
- Add typed `linter` support to the YAML manifest and validate it at boot.
- Spring AI natively offers `CallAdvisor` APIs to intercept LLM responses.
- Implement a `LinterCallAdvisor` that inspects the LLM generation, runs the configured regex or external logic gate defined in the skill manifest, and appends a "Hint" to the prompt to force the LLM to retry. This removes the need for Bifrost to build an orchestration retry loop manually.
- **Token Burn Protection:** Ensure the `LinterCallAdvisor` enforces a `max_retries` setting to prevent a stubborn model from spiraling into an infinite retry loop with the linter.
- Record linter outcomes and retry counts so the full validation loop is observable and testable.

## Deliverables
- Secured `AccessGuard`.
- Interface-driven VFS implementation using temp directory Spring Resources.
- Typed YAML linter configuration and startup validation.
- Spring AI CallAdvisor implementation for the linter, including bounded retries and observability hooks.

## Exit Criteria
- Unauthenticated or unauthorized LLM prompts to specific tools throw `AccessDeniedException`s.
- Ad-hoc retries work organically through the CallAdvisor layer.
- Invalid linter definitions fail fast at startup.
- Linter retries are bounded and observable.
- `ref://` payloads are transparently converted to InputStreams using standard Spring abstractions.
