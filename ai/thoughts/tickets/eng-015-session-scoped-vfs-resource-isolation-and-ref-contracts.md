# Ticket: eng-015-session-scoped-vfs-resource-isolation-and-ref-contracts.md
## Issue: Mature the Existing VFS Into a Session-Scoped Spring Resource Boundary

### Why This Ticket Exists
Phase 4 describes introducing a `VirtualFileSystem` abstraction, but that interface already exists in the codebase. What is missing is the next step: making the VFS contract robust enough for session isolation, Spring `Resource` interoperability, and future backend swaps without leaking local-file assumptions into the rest of the runtime.

This ticket focuses on strengthening the contract that Phase 3 started rather than recreating it.

---

## Goal
Refine the current VFS and ref-resolution path so session-scoped `ref://` payloads resolve through stable Spring `Resource` abstractions with clear isolation and extension points.

The main outcome should be:

- session-scoped resource resolution is explicit and durable
- ref handling stays backend-agnostic outside the VFS package
- the MVP temp-directory implementation is ready for future `S3Resource`-style backends

---

## Non-Goals
This ticket should **not** introduce:

- actual S3 support
- linter behavior
- authorization policy changes beyond what is needed for session isolation
- a large rewrite of tool invocation plumbing

---

## Required Outcomes

### Functional
- Keep `VirtualFileSystem` as the stable abstraction and review whether its API needs to expose more than simple `resolve(...)` to support later backends cleanly.
- Ensure session namespacing is enforced centrally for every `ref://` lookup.
- Preserve `FileSystemResource` as the MVP backend rooted in the system temp directory.
- Ensure `ref://` values resolve transparently to Spring `Resource`, `InputStream`, and related supported parameter types through the existing ref resolver/invocation path.
- Add explicit protections for invalid refs, path escape attempts, and missing resources.

### Structural
- Local filesystem details remain inside the temp-directory VFS implementation.
- The rest of the runtime depends on Spring `Resource` semantics, not `Path` semantics.
- The API is shaped so a future remote-storage implementation can replace the backend without changing tool execution contracts.

### Testing
- Tests prove refs are isolated by `sessionId`.
- Tests prove path traversal attempts are rejected.
- Tests prove `ref://` payloads resolve into supported Spring resource forms.
- Tests prove missing refs fail clearly and predictably.

---

## Suggested Files
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VirtualFileSystemTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`

---

## Acceptance Criteria
- `ref://` payloads resolve through a session-scoped VFS boundary backed by Spring `Resource`.
- Session isolation and traversal protections are covered by tests.
- Runtime code outside the VFS package does not need to know whether the backend is local disk or a future remote resource store.
- The temp-directory implementation remains the default MVP backend.

---

## Definition of Done
This ticket is done when the current VFS is a stable session-isolated Spring resource boundary that the rest of the runtime can depend on without local-backend coupling.
