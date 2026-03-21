---
date: 2026-03-21T00:11:47.8198253-07:00
researcher: mgiacomi
git_commit: 17c89fdd8de87e05f19d60ea52307609107db6e8
branch: main
repository: bifrost
topic: "Research the current implementation for ENG-015 session-scoped VFS resource isolation and ref contracts"
tags: [research, codebase, vfs, ref-resolution, spring-resource, bifrost-session]
status: complete
last_updated: 2026-03-21
last_updated_by: mgiacomi
model: GPT-5
---

# Research: ENG-015 session-scoped VFS resource isolation and ref contracts

**Date**: 2026-03-21T00:11:47.8198253-07:00
**Researcher**: mgiacomi
**Git Commit**: `17c89fdd8de87e05f19d60ea52307609107db6e8`
**Branch**: `main`
**Repository**: `bifrost`

## Research Question

How does the current codebase implement session-scoped `ref://` resolution, Spring `Resource` interoperability, and the surrounding runtime contract described by `eng-015-session-scoped-vfs-resource-isolation-and-ref-contracts.md`?

## Summary

The current implementation already places `ref://` handling behind a `VirtualFileSystem` interface that returns Spring `Resource` objects rather than `Path` values. The default auto-configured backend is `SessionLocalVirtualFileSystem`, which roots all lookups under `${java.io.tmpdir}/bifrost-vfs/<sessionId>` and resolves refs to `FileSystemResource` instances. Session namespacing is derived from `BifrostSession.getSessionId()`, and traversal checks are enforced inside the VFS implementation before a resource is returned.

`DefaultRefResolver` sits above the VFS boundary. It detects exact `ref://...` string leaves, resolves them through the `VirtualFileSystem`, and recursively rewrites nested maps, lists, and arrays via the `RefResolver` default methods. Runtime invocation flows through `CapabilityExecutionRouter`, which applies `refResolver.resolveArguments(...)` before invoking deterministic capabilities.

At the method-binding layer, `SkillMethodBeanPostProcessor` treats resolved ref payloads as Spring `Resource` values and converts them into supported parameter forms including `Resource`, `String`, `byte[]`, `InputStream`, nested typed records, and typed collections. The tests in `vfs`, `core`, and `runtime.tool` document the current behavior for session isolation, traversal rejection, exact-ref matching, nested argument resolution, and Spring-resource-backed method invocation.

## Detailed Findings

### VFS Contract

`VirtualFileSystem` is a minimal interface with a single method, `Resource resolve(BifrostSession session, String ref)`, so the public contract presented to callers is a session plus a ref string producing a Spring `Resource` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java:6`).

Because the interface returns `Resource`, code above the VFS boundary consumes Spring resource semantics instead of filesystem `Path` semantics (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java:8`).

### Session-Scoped Local Backend

The default concrete implementation is `SessionLocalVirtualFileSystem`, which stores an absolute normalized root directory in its constructor (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:12`-`15`).

During resolution, the implementation:

- requires a non-null session
- derives the session namespace root with `sessionRoot(session)`
- strips and validates the `ref://` prefix
- resolves the remaining relative path under the session root
- normalizes the final path
- rejects lookups whose normalized path no longer starts with the session root
- wraps the result in a `FileSystemResource`
- rejects missing resources

This behavior is implemented in `resolve(...)` and `normalizeRef(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:18`-`31`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:38`-`43`).

`sessionRoot(BifrostSession session)` maps each session to `<rootDirectory>/<sessionId>`, which is where namespacing is derived (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:34`-`35`).

### Session Identity Source

`BifrostSession` stores `sessionId` as required constructor state, validates it as non-blank, and exposes it through `getSessionId()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:21`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:58`-`79`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/BifrostSession.java:276`-`281`).

The VFS implementation uses this `sessionId` directly when computing the session root (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:34`-`35`).

### Ref Resolution Contract

`RefResolver` defines `resolveArgument(Object value, BifrostSession session)` and provides a default `resolveArguments(...)` implementation that recursively walks nested maps, lists, and arrays before delegating leaf values back to `resolveArgument(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/RefResolver.java:11`-`42`).

`DefaultRefResolver` uses a strict exact-match pattern, `^ref://\\S+$`, so only entire strings that match the scheme without whitespace are treated as refs (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java:9`).

When a value is an exact ref string, `DefaultRefResolver` resolves it through `virtualFileSystem.resolve(session, text)` and returns the resulting `Resource` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java:17`-`27`).

### Runtime Invocation Path

The default auto-configuration registers:

- `VirtualFileSystem` as `new SessionLocalVirtualFileSystem(Paths.get(System.getProperty("java.io.tmpdir"), "bifrost-vfs"))`
- `RefResolver` as `new DefaultRefResolver(virtualFileSystem)`
- `CapabilityExecutionRouter` with the injected `RefResolver`

This wiring lives in `BifrostAutoConfiguration` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:121`-`142`).

`CapabilityExecutionRouter.execute(...)` enforces access control first, then sends deterministic execution through `capability.invoker().invoke(refResolver.resolveArguments(safeArguments, session))`, which means ref resolution happens before deterministic tool invocation (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:45`-`67`).

For YAML skills without a mapped target, the router instead delegates to `ExecutionCoordinator`; the deterministic route is the branch that directly consumes `refResolver.resolveArguments(...)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:55`-`66`).

### Spring Resource Interoperability in Method Binding

`SkillMethodBeanPostProcessor` is the binding layer for deterministic Java `@SkillMethod` capabilities. It registers annotated bean methods as capabilities and later binds invocation arguments before reflective method execution (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:58`-`90`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:93`-`130`).

When raw invocation values already contain Spring `Resource` instances from ref resolution, the binder supports these forms:

- direct `Resource` parameters via `convertToResource(...)`
- `byte[]` via `convertToBytes(...)`
- `InputStream` via `convertToInputStream(...)`
- `String` by reading the `Resource` as UTF-8

This logic is implemented in `convertArgument(...)`, `materializeValue(...)`, and the conversion helpers (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:133`-`217`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:242`-`300`).

The same binder also materializes resource leaves inside nested maps, typed collections, arrays, and record-like object graphs before delegating to Jackson conversion (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:163`-`217`).

### Test Coverage for the Current Contract

`VirtualFileSystemTest` covers:

- session isolation between `session-1` and `session-2`
- binary byte preservation
- traversal rejection for `ref://../other-session/secret.txt`
- invalid ref syntax rejection
- nested path resolution within a session namespace

These tests are in `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VirtualFileSystemTest.java:22`-`89`.

`RefResolverTest` covers:

- exact-ref matching only
- recursive resolution inside nested maps, lists, and arrays
- returning `Resource` objects for binary payloads
- leaving non-exact ref-like strings untouched
- propagating underlying VFS resolution failures

These tests are in `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java:20`-`106`.

`SkillMethodBeanPostProcessorTest` covers deterministic method binding from resource-backed values into:

- `String`
- `byte[]`
- `Resource`
- `InputStream`
- nested typed record parameters
- typed collections

These tests are in `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:113`-`207`.

`ToolCallbackFactoryTest` includes a router-level test showing that a JSON argument payload containing `ref://artifacts/input.txt` is resolved before deterministic execution returns `"child:resolved-content"` (`bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java:88`-`107`).

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java:6-8` - VFS interface returning Spring `Resource`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:18-31` - Session-rooted local ref resolution, traversal check, and missing-resource handling.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:34-43` - Session root calculation and `ref://` syntax normalization.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/RefResolver.java:15-42` - Recursive traversal of maps, lists, and arrays during argument resolution.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java:17-27` - Exact-ref detection and conversion to `Resource`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:52-66` - Access check plus deterministic ref resolution before invocation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:138-160` - Parameter-type dispatch for `Resource`, `byte[]`, `InputStream`, and `String`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:163-217` - Recursive materialization of nested resource-backed structures.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java:121-142` - Default bean wiring for VFS, ref resolver, and execution router.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VirtualFileSystemTest.java:22-89` - VFS behavior tests for isolation, traversal rejection, syntax rejection, and nested paths.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java:20-106` - Exact ref semantics and recursive argument resolution tests.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:113-207` - Resource-backed deterministic invocation tests.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java:88-107` - Router-level proof that refs resolve before deterministic execution.

## Architecture Documentation

The current architecture separates responsibilities into three layers:

1. `VirtualFileSystem` defines the storage boundary and returns Spring `Resource`.
2. `RefResolver` detects and rewrites `ref://` leaves in input argument graphs.
3. `SkillMethodBeanPostProcessor` converts the resolved `Resource` values into the Java method parameter types expected by deterministic capabilities.

Session isolation currently lives inside the local VFS implementation, where the session namespace is computed from `BifrostSession.sessionId`. The runtime above that layer interacts with Spring `Resource` objects and does not call filesystem APIs directly in the ref-resolution path covered by this research.

## Historical Context (from ai/thoughts/)

Phase 2 describes the coordinator resolving `ref://` pointers into Spring-managed resource payloads before normal domain logic executes (`ai/thoughts/phases/phase2.md:29`).

Phase 4 describes the planned shape of the VFS boundary: a stable `VirtualFileSystem` interface, a temp-directory `FileSystemResource` MVP backend, session-based prefixing, and transparent `ref://` conversion using Spring abstractions (`ai/thoughts/phases/phase4.md:23`, `ai/thoughts/phases/phase4.md:45`).

The `ai/thoughts` README describes `readData / writeData` as `Resource`-backed Spring interfaces that pass `ref://...` pointers between skills (`ai/thoughts/phases/README.md:25`).

The ENG-015 ticket itself frames the research target as maturing the already-existing VFS interface into a stronger session-scoped Spring `Resource` boundary (`ai/thoughts/tickets/eng-015-session-scoped-vfs-resource-isolation-and-ref-contracts.md:5`-`18`).

## Related Research

No existing documents were present under `ai/thoughts/research/` at the time of this research run.

## Open Questions

- No additional open questions were recorded during this research pass.
