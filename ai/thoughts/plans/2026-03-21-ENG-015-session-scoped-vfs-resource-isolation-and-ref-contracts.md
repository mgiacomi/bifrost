# ENG-015 Session-Scoped VFS Resource Isolation And Ref Contracts Implementation Plan

## Overview

Strengthen the existing `ref://` pipeline so session isolation, ref validation, and Spring `Resource` interoperability are enforced at a single VFS boundary that remains backend-agnostic for future non-filesystem implementations.

## Current State Analysis

The current code already routes `ref://` payloads through a `VirtualFileSystem` abstraction that returns Spring `Resource` rather than `Path`, which is the right architectural direction for this ticket. The gaps are mostly contract maturity and boundary clarity, not missing infrastructure.

Today, `VirtualFileSystem` exposes only `Resource resolve(BifrostSession session, String ref)` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java:6](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java#L6), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java:8](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java#L8)). The default backend is `SessionLocalVirtualFileSystem`, which computes `<root>/<sessionId>`, normalizes the target path, rejects traversal, and returns a `FileSystemResource` ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:19](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java#L19), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:34](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java#L34), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:38](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java#L38)).

Above that layer, `DefaultRefResolver` recognizes only exact ref strings and turns them into `Resource` values ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java:9](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java#L9), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java:18](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java#L18)), `RefResolver` recursively rewrites nested maps/lists/arrays ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/RefResolver.java:15](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/RefResolver.java#L15)), `CapabilityExecutionRouter` resolves refs before deterministic invocation ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:66](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java#L66)), and `SkillMethodBeanPostProcessor` converts `Resource` payloads into `String`, `byte[]`, `InputStream`, nested records, and typed collections ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:133](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java#L133), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:163](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java#L163), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:242](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java#L242)).

Existing tests already prove isolation, traversal rejection, exact-ref semantics, nested resolution, and resource-backed method binding ([bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VirtualFileSystemTest.java:23](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VirtualFileSystemTest.java#L23), [bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java:21](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java#L21), [bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java:114](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java#L114), [bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java:89](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java#L89)). The plan should build on those seams instead of introducing a new pipeline.

## Desired End State

After this ticket:

- every `ref://` lookup is parsed and validated in a single VFS-owned contract
- session scoping is enforced centrally and consistently for all lookups
- callers outside `com.lokiscale.bifrost.vfs` interact with Spring `Resource` semantics only
- the default backend remains temp-directory + `FileSystemResource`
- the VFS contract is explicit enough that a future remote backend can replace the local implementation without changing the resolver, router, or method-binding contracts

Verification of the end state means:

- invalid refs fail before any backend-specific lookup occurs
- traversal attempts cannot escape the session namespace
- missing refs produce deterministic, session-aware failures
- deterministic Java skill methods continue to receive `Resource`, `String`, `byte[]`, and `InputStream` payloads from `ref://` arguments without needing filesystem knowledge

### Key Discoveries:
- `VirtualFileSystem` already returns Spring `Resource`, so the ticket is about contract hardening rather than abstraction replacement ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java:8](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java#L8)).
- Session isolation currently lives only in the local implementation via `sessionRoot(session)` and normalized path checks ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:21](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java#L21), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:34](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java#L34)).
- The runtime already resolves refs before deterministic execution, so strengthening the VFS and resolver contracts gives broad coverage without changing invocation plumbing ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:66](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java#L66)).
- The binder already treats `Resource` as the canonical payload boundary, which is the behavior we want to preserve for future backends ([bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:141](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java#L141), [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:147](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java#L147)).

## What We're NOT Doing

- Adding S3 or any remote storage implementation
- Reworking tool-callback execution flow outside the existing ref-resolution boundary
- Changing authorization behavior beyond preserving session-scoped lookup isolation
- Adding linter-specific behavior or non-ref payload transformations
- Introducing a write API for VFS storage in this ticket

## Implementation Approach

Keep the runtime contract centered on Spring `Resource`, but make the VFS package own a stronger typed ref contract instead of passing raw strings all the way through resolution. The practical implementation choice for this ticket is:

1. Introduce a small VFS-owned ref value object that parses and validates `ref://...` once.
2. Extend `VirtualFileSystem` with a typed resolution entrypoint while preserving compatibility for current call sites.
3. Centralize missing-ref and traversal failures in the VFS implementation so the resolver becomes a thin adapter from argument graphs to VFS resources.
4. Leave local-disk details inside `SessionLocalVirtualFileSystem`, with no `Path` exposure above the VFS package.

This gives future backends a cleaner seam: they can map a validated logical ref into any Spring `Resource` implementation without requiring the rest of the runtime to understand local filesystem paths.

### Implementation Guardrails

- Treat `SessionLocalVirtualFileSystem` as the final security boundary. Even if `VfsRef` rejects obviously malformed inputs, traversal protection must still be enforced by the backend's normalized resolved-path check.
- Keep the VFS API migration additive. Add `resolve(BifrostSession session, VfsRef ref)` as the stronger seam, but preserve `resolve(BifrostSession session, String ref)` as a default bridge during this ticket.
- Keep `VfsRef` intentionally small. It should own scheme validation, non-empty payload validation, and canonical logical ref representation, but not session logic, write concerns, or backend-specific policy.
- Remove duplicated existence/error checks from `DefaultRefResolver` once the VFS owns those failures. The resolver should remain a thin exact-leaf detector plus delegator.
- Preserve the runtime boundary above the VFS. `CapabilityExecutionRouter` and `SkillMethodBeanPostProcessor` should continue consuming `Resource`-based semantics and should only change if the new contract exposes redundant logic.
- Treat error messages as part of the contract. Tests should lock down distinct failure categories for malformed refs, session-namespace escapes, and session-scoped missing resources.
- If `VfsRef` gains meaningful logic, give it a dedicated unit test class rather than overloading `VirtualFileSystemTest` with all parsing behavior.

## Phase 1: Harden The VFS Contract

### Overview

Move raw-ref parsing and validation into a VFS-owned contract so backends resolve a typed session-scoped ref instead of each implementation reinterpreting arbitrary strings.

### Changes Required:

#### 1. Add a typed ref contract in the VFS package
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java`
**Changes**: Add an overload or primary method that resolves a parsed ref type, and keep a bridging default method for legacy `String` call sites during the migration.

```java
public interface VirtualFileSystem {

    default Resource resolve(BifrostSession session, String ref) {
        return resolve(session, VfsRef.parse(ref));
    }

    Resource resolve(BifrostSession session, VfsRef ref);
}
```

#### 2. Introduce immutable VFS ref parsing/validation
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VfsRef.java`
**Changes**: Add a value object that owns scheme validation, empty-path rejection, and canonical logical relative-path representation. Keep it backend-agnostic and do not rely on it as the only traversal defense.

```java
public record VfsRef(String raw, String relativePath) {

    public static VfsRef parse(String raw) {
        // validate ref:// prefix, non-empty path, and illegal traversal tokens
    }
}
```

#### 3. Update the local backend to resolve only typed refs
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java`
**Changes**: Replace string slicing with `VfsRef`, keep `<root>/<sessionId>/<relativePath>` lookup logic, and keep traversal/missing-resource enforcement there as the authoritative safety check. Remove any API surface that leaks implementation detail beyond tests.

```java
@Override
public Resource resolve(BifrostSession session, VfsRef ref) {
    Path sessionRoot = sessionRoot(session);
    Path resolved = sessionRoot.resolve(ref.relativePath()).normalize();
    if (!resolved.startsWith(sessionRoot)) {
        throw new IllegalArgumentException("Ref '%s' escapes the session namespace".formatted(ref.raw()));
    }
    Resource resource = new FileSystemResource(resolved);
    if (!resource.exists()) {
        throw new IllegalArgumentException("Unknown ref '%s' for session '%s'".formatted(ref.raw(), session.getSessionId()));
    }
    return resource;
}
```

### Success Criteria:

#### Automated Verification:
- [x] Starter module tests pass: `mvn -pl bifrost-spring-boot-starter test`
- [x] Focused VFS tests pass: `mvn -pl bifrost-spring-boot-starter -Dtest=VirtualFileSystemTest,RefResolverTest test`
- [x] Project compiles with the updated VFS contract: `mvn test -DskipTests`

#### Manual Verification:
- [ ] Code inspection confirms no caller outside `com.lokiscale.bifrost.vfs` depends on local `Path` semantics for ref resolution.
- [ ] Error messages for malformed refs, traversal attempts, and missing refs are distinct and understandable.
- [ ] The contract reads as backend-agnostic: a future `S3Resource` implementation could satisfy it without changing router or binder contracts.
- [ ] Existing temp-directory behavior remains the default lookup model.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual review of the new VFS contract is acceptable before proceeding to the next phase.

---

## Phase 2: Align Ref Resolution With The Hardened Contract

### Overview

Make `DefaultRefResolver` a thin, predictable adapter that recognizes exact ref leaves, delegates to the VFS contract, and leaves all backend-specific existence/error behavior inside the VFS.

### Changes Required:

#### 1. Simplify resolver behavior around the typed contract
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java`
**Changes**: Keep strict leaf matching, but delegate parsing/validation to the new VFS contract and remove duplicated existence checks that the backend already owns. The resolver should remain a thin adapter, not a second policy layer.

```java
@Override
public Object resolveArgument(Object value, BifrostSession session) {
    if (!(value instanceof String text) || !STRICT_REF_PATTERN.matcher(text).matches()) {
        return value;
    }
    return virtualFileSystem.resolve(session, text);
}
```

#### 2. Preserve recursive traversal semantics
**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/RefResolver.java`
**Changes**: Keep the current recursive map/list/array behavior intact, but add tests proving typed VFS resolution still applies to nested structures and array inputs after the contract change.

#### 3. Confirm runtime and binder contracts remain Spring-resource based
**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java`

**Changes**: Prefer no functional changes here unless the new VFS contract reveals redundant or misleading logic. The primary task is to keep these layers consuming `Resource` and not regress supported parameter conversions.

### Success Criteria:

#### Automated Verification:
- [x] Resolver and binder tests pass: `mvn -pl bifrost-spring-boot-starter -Dtest=RefResolverTest,SkillMethodBeanPostProcessorTest,ToolCallbackFactoryTest test`
- [x] Full starter test suite still passes: `mvn -pl bifrost-spring-boot-starter test`
- [x] No compile regressions across modules: `mvn test -DskipTests`

#### Manual Verification:
- [ ] Reviewing `DefaultRefResolver` shows backend-specific error handling is no longer duplicated above the VFS.
- [ ] Reviewing `CapabilityExecutionRouter` confirms refs are still resolved before deterministic invocation.
- [ ] Reviewing `SkillMethodBeanPostProcessor` confirms it still operates only on `Resource` and converted payload types, not filesystem-specific types.
- [ ] Nested map/list/array ref behavior remains unchanged from a caller perspective.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the resolver and runtime boundary still match the intended contract before proceeding to the next phase.

---

## Phase 3: Expand Regression Coverage Around Isolation And Resource Semantics

### Overview

Lock in the contract with tests that make the intended backend boundary explicit and guard against regressions as future storage backends are added.

### Changes Required:

#### 1. Expand VFS tests around the typed contract
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VirtualFileSystemTest.java`
**Changes**: Keep the current isolation/traversal coverage and add assertions around session-aware missing-resource errors and backend enforcement of namespace boundaries. If `VfsRef` contains non-trivial parsing rules, cover those in a dedicated `VfsRefTest`.

```java
@Test
void rejectsMalformedTypedRefsBeforeLookup() {
    assertThatThrownBy(() -> VfsRef.parse("ref://"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

#### 2. Expand resolver tests for exact-match and failure propagation
**File**: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java`
**Changes**: Preserve exact-leaf matching tests, add verification that malformed refs are rejected through the VFS contract, and ensure nested containers still receive `Resource` values rather than local-path artifacts.

#### 3. Keep end-to-end resource conversion coverage
**Files**:
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`

**Changes**: Retain current `Resource`/`String`/`byte[]`/`InputStream` conversion coverage and add any missing assertions needed to prove the runtime remains agnostic to the concrete `Resource` backend.

### Success Criteria:

#### Automated Verification:
- [x] Focused contract tests pass: `mvn -pl bifrost-spring-boot-starter -Dtest=VirtualFileSystemTest,RefResolverTest,SkillMethodBeanPostProcessorTest,ToolCallbackFactoryTest test`
- [x] Full repository tests pass: `mvn test`
- [x] Maven validation still passes under repo constraints: `mvn -q validate`

#### Manual Verification:
- [ ] Test names and assertions make the session-isolation and Spring-resource contract obvious to future contributors.
- [ ] There is clear regression coverage for malformed refs, traversal attempts, missing refs, and supported resource-backed parameter types.
- [ ] The test suite documents that backend substitutions should happen inside the VFS package only.
- [ ] Auto-config still defaults to the temp-directory VFS backend after the contract changes.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the expanded regression coverage is sufficient before closing the ticket.

## Testing Strategy

### Unit Tests:
- Add direct tests for `VfsRef.parse(...)` covering missing scheme, blank payload, and valid nested refs. Keep traversal-defense assertions anchored in the backend tests unless `VfsRef` intentionally owns a narrow structural rejection rule.
- Extend `VirtualFileSystemTest` to verify session-specific missing-resource messages and typed-ref integration with the local backend.
- Extend `RefResolverTest` to verify nested map/list/array traversal still returns `Resource` objects and still ignores non-exact ref-like strings.

### Integration Tests:
- Keep `ToolCallbackFactoryTest` as the router-level proof that JSON tool arguments containing `ref://...` are resolved before deterministic execution.
- Use `SkillMethodBeanPostProcessorTest` as the deterministic method-binding proof that `Resource` values continue to materialize into supported Java parameter types.

**Note**: Prefer a dedicated testing plan artifact created via `/testing_plan` for full details, including any failing-test-first sequence and exit criteria.

### Manual Testing Steps:
1. Create two `BifrostSession` instances with different `sessionId` values and confirm the same `ref://artifacts/...` string resolves only within the owning session.
2. Exercise malformed and traversal refs and confirm failures occur before any caller receives a usable resource.
3. Invoke a deterministic capability that accepts `String`, `Resource`, and `InputStream` parameters from ref-backed payloads and confirm the method body never needs local filesystem APIs.

## Performance Considerations

The main path remains a single ref parse plus a single backend resolution per exact ref leaf, so this ticket should not materially change runtime cost. Avoid adding repeated existence checks above the VFS, since the backend already performs that work and duplicated I/O would make future remote backends more expensive.

## Migration Notes

The safest migration is additive first:

- add `VfsRef` and the typed `VirtualFileSystem.resolve(...)` entrypoint
- keep the existing `String`-based method as a default bridge
- update `SessionLocalVirtualFileSystem` and `DefaultRefResolver` to use the new contract
- leave higher layers untouched unless a redundant check becomes misleading
- standardize error messaging while preserving the current session-scoped behavior

This keeps the change small and avoids a large refactor across all current call sites.

## References

- Original ticket: `ai/thoughts/tickets/eng-015-session-scoped-vfs-resource-isolation-and-ref-contracts.md`
- Related research: `ai/thoughts/research/2026-03-21-ENG-015-session-scoped-vfs-resource-isolation-and-ref-contracts.md`
- Current VFS abstraction: [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java:6](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java#L6)
- Current local backend: [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:19](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java#L19)
- Current resolver boundary: [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java:18](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java#L18)
- Deterministic invocation boundary: [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:66](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java#L66)
- Resource conversion boundary: [bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:133](C:/opendev/code/bifrost/bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java#L133)
