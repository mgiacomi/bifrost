# Phase 4 - Advanced Integrations (Security, Storage, Linter)

## Goal
Leverage advanced Spring integrations to harden the platform: Securing discovery with Spring Security, abstracting VFS with Spring Resources, and utilizing Spring AI CallAdvisors for the validation loop.

## Primary Outcomes
- RBAC enforces which YAML skills or `@SkillMethod`s can be executed by the sub-agent based on the Principal.
- `BifrostSession` automatically isolates internal logs and payloads to standard Spring `FileSystemResource` or `S3Resource`.
- Linter validation loops automatically correct models.

## Scope
- Security context propagation.
- External VFS abstraction limit tracking.
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
- Spring AI natively offers `CallAdvisor` APIs to intercept LLM responses.
- Implement a `LinterCallAdvisor` that inspects the LLM generation, runs the configured regex or external logic gate defined in the skill manifest, and appends a "Hint" to the prompt to force the LLM to retry. This removes the need for Bifrost to build an orchestration retry loop manually.
- **Token Burn Protection:** Ensure the `LinterCallAdvisor` enforces a `max_retries` setting to prevent a stubborn model from spiraling into an infinite retry loop with the linter.

## Deliverables
- Secured `AccessGuard`.
- Interface-driven VFS implementation using temp directory Spring Resources.
- Spring AI CallAdvisor implementation for the Linter.

## Exit Criteria
- Unauthenticated or unauthorized LLM prompts to specific tools throw `AccessDeniedException`s.
- Ad-hoc retries work organically through the CallAdvisor layer.
- `ref://` payloads are transparently converted to InputStreams using standard Spring abstractions.
