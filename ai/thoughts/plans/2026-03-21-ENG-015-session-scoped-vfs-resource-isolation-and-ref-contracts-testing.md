# ENG-015 Session-Scoped VFS Resource Isolation And Ref Contracts Testing Plan

## Change Summary
- Harden the existing `ref://` resolution path around a VFS-owned typed ref contract while preserving Spring `Resource` as the runtime boundary.
- Keep `SessionLocalVirtualFileSystem` as the authoritative session-isolation and traversal-enforcement layer.
- Simplify `DefaultRefResolver` so it remains a thin exact-ref detector and delegator instead of duplicating backend policy.
- Preserve deterministic capability support for `Resource`, `String`, `byte[]`, `InputStream`, nested records, and typed collections.

## Impacted Areas
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VirtualFileSystem.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/RefResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/VfsRef.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VirtualFileSystemTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`

## Risk Assessment
- High risk: traversal protection accidentally shifts from backend enforcement to parser-only logic, weakening the real security boundary.
- High risk: the new typed contract breaks current `resolve(session, String ref)` call sites or changes error semantics unexpectedly.
- Medium risk: `DefaultRefResolver` stops returning `Resource` payloads consistently for nested maps, lists, or arrays.
- Medium risk: method binding regresses for `String`, `byte[]`, `InputStream`, or nested typed payloads after the VFS contract change.
- Medium risk: error messages become inconsistent across malformed refs, missing refs, and namespace escapes, making debugging harder.
- Low risk: auto-configuration or router-level execution still compiles but no longer demonstrates end-to-end ref resolution before deterministic invocation.

## Existing Test Coverage
- `VirtualFileSystemTest` already covers session isolation, binary preservation, traversal rejection, invalid ref syntax rejection, and nested same-session lookups.
- `RefResolverTest` already covers exact-leaf matching, nested map/list/array traversal, binary resource preservation, and failure propagation.
- `SkillMethodBeanPostProcessorTest` already covers `Resource` conversion into `String`, `byte[]`, `Resource`, `InputStream`, nested records, and typed collections.
- `ToolCallbackFactoryTest` already proves refs are resolved before deterministic execution.
- Current gap: there is no direct test yet for the new typed ref contract, the `String`-to-typed bridge on `VirtualFileSystem`, or the intended removal of duplicated error handling from `DefaultRefResolver`.

## Bug Reproduction / Failing Test First
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VfsRefTest.java`
- Arrange/Act/Assert outline:
  - Arrange malformed and valid raw refs such as `"ref://"` and `"ref://artifacts/report.txt"`.
  - Act by calling `VfsRef.parse(...)`.
  - Assert malformed refs throw `IllegalArgumentException` with the standardized malformed-ref category, while valid refs preserve a canonical logical relative path.
- Expected failure (pre-fix):
  - The test will not compile or will fail because `VfsRef` and its parsing contract do not exist yet.

This is not a classic bug fix; it is contract hardening. The "failing test first" should therefore establish the new public seam and force the implementation to define it precisely before any refactoring of the existing path.

## Tests to Add/Update
### 1) `VfsRefTest.parseRejectsMissingSchemeOrEmptyPayload`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VfsRefTest.java`
- What it proves: the new typed ref contract rejects malformed refs before backend lookup and standardizes the malformed-ref error category.
- Fixtures/data: raw strings like `"artifacts/file.txt"`, `"ref://"`, and `"ref://artifacts/file.txt"`.
- Mocks: none.

### 2) `VfsRefTest.parsePreservesCanonicalLogicalPathForValidRefs`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VfsRefTest.java`
- What it proves: valid refs produce the expected logical relative path without introducing session logic or backend coupling.
- Fixtures/data: nested refs such as `"ref://artifacts/reports/2026/summary.txt"`.
- Mocks: none.

### 3) `VirtualFileSystemTest.resolveStringBridgeDelegatesThroughTypedContract`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VirtualFileSystemTest.java`
- What it proves: existing `resolve(session, String ref)` callers keep working through the additive bridge and receive the same resource semantics as the typed method.
- Fixtures/data: temp-directory session roots and a known file under `artifacts/`.
- Mocks: none.

### 4) `VirtualFileSystemTest.missingRefsFailWithSessionAwareMessage`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VirtualFileSystemTest.java`
- What it proves: missing refs still fail inside the backend with a distinct message that includes the session context.
- Fixtures/data: a session with no matching file under its temp root.
- Mocks: none.

### 5) `VirtualFileSystemTest.traversalDefenseRemainsEnforcedByBackend`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/VirtualFileSystemTest.java`
- What it proves: even after adding `VfsRef`, the local backend's normalized-path check remains the authoritative namespace escape defense.
- Fixtures/data: `ref://../other-session/secret.txt` and optionally deeper escape attempts.
- Mocks: none.

### 6) `RefResolverTest.exactRefLeavesDelegateWithoutDuplicateExistenceChecks`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java`
- What it proves: `DefaultRefResolver` delegates matching ref leaves directly to the VFS and does not re-check `Resource.exists()` or add a second policy layer.
- Fixtures/data: a `VirtualFileSystem` test double returning a `Resource` whose `exists()` behavior would expose duplicate checks if they still happen.
- Mocks: lightweight fake or lambda VFS implementation; avoid mocking more than needed.

### 7) `RefResolverTest.nestedContainersStillResolveToResourceLeaves`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/vfs/RefResolverTest.java`
- What it proves: nested maps, lists, and arrays still resolve exact `ref://` leaves into `Resource` values after the contract change.
- Fixtures/data: nested argument graphs matching the current test style.
- Mocks: lambda VFS returning `ByteArrayResource`.

### 8) `SkillMethodBeanPostProcessorTest.resourceBackedConversionsRemainStable`
- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessorTest.java`
- What it proves: deterministic method binding remains unchanged for `String`, `byte[]`, `Resource`, `InputStream`, nested records, and typed collections.
- Fixtures/data: existing `ByteArrayResource` payload fixtures.
- Mocks: none.

### 9) `ToolCallbackFactoryTest.resolvesRefArgumentsBeforeDeterministicExecution`
- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/tool/ToolCallbackFactoryTest.java`
- What it proves: the router-level path still resolves `ref://` arguments before deterministic tool execution after the VFS contract change.
- Fixtures/data: existing callback JSON payload with a `ref://artifacts/input.txt` value.
- Mocks: keep current mocked planning/state services and minimal ref resolver setup.

## How to Run
- Compile only: `mvn test -DskipTests`
- Focused new contract tests: `mvn -pl bifrost-spring-boot-starter -Dtest=VfsRefTest,VirtualFileSystemTest,RefResolverTest test`
- Runtime boundary regression tests: `mvn -pl bifrost-spring-boot-starter -Dtest=SkillMethodBeanPostProcessorTest,ToolCallbackFactoryTest test`
- Full starter module suite: `mvn -pl bifrost-spring-boot-starter test`
- Full repository suite before merge: `mvn test`
- Validation pass: `mvn -q validate`

## Exit Criteria
- [ ] A failing test exists first for the new typed ref contract and fails pre-fix.
- [x] The additive `resolve(session, String ref)` bridge is covered by tests and passes post-fix.
- [x] Traversal rejection remains enforced by `SessionLocalVirtualFileSystem`, not only by parser logic.
- [x] Missing refs fail with a distinct session-aware message.
- [x] `DefaultRefResolver` still resolves exact ref leaves and nested containers without duplicating backend existence checks.
- [x] `SkillMethodBeanPostProcessorTest` still proves `Resource`-backed conversion into supported Java parameter types.
- [x] `ToolCallbackFactoryTest` still proves end-to-end ref resolution occurs before deterministic execution.
- [x] `mvn -pl bifrost-spring-boot-starter test` passes.
- [x] `mvn test` and `mvn -q validate` pass before merge.
- [ ] Manual verification confirms the runtime above `com.lokiscale.bifrost.vfs` remains `Resource`-centric and backend-agnostic.
