# ENG-001 Parent POM Bootstrap Implementation Plan

## Overview

Establish the foundational Maven build for Bifrost by creating a root aggregator parent POM, generating Maven Wrapper scripts, and enforcing Java/Maven version governance. This creates the build baseline required for follow-on module tickets.

## Current State Analysis

- The ENG-001 ticket defines explicit acceptance criteria for wrapper scripts, parent POM structure, BOM imports, and plugin governance (`ai/thoughts/tickets/eng-001-parent-pom.md:9-24`).
- Phase 1 documentation confirms this is the prerequisite for `bifrost-spring-boot-starter` and `bifrost-sample` module work (`ai/thoughts/phases/phase1.md:19-37`).
- The repository currently has `.mvn/wrapper/maven-wrapper.properties` but does not yet contain root wrapper scripts (`mvnw`, `mvnw.cmd`) or a root `pom.xml` (`.mvn/wrapper/maven-wrapper.properties:1-3`).
- The `ai/thoughts/plans/` directory is currently empty, so this will be the first implementation plan artifact for this codebase.

## Desired End State

A root-level Maven build exists with:

1. A valid aggregator `pom.xml` using `com.lokiscale.bifrost` and `<packaging>pom</packaging>`.
2. Java 21 compiler release and core build properties configured.
3. `<dependencyManagement>` importing:
   - `org.springframework.boot:spring-boot-dependencies:3.5.11`
   - `org.springframework.ai:spring-ai-bom:1.1.2`
4. `<build><pluginManagement>` configuring:
   - `maven-compiler-plugin`
   - `maven-surefire-plugin` for JUnit Jupiter
   - `maven-enforcer-plugin` enforcing minimum Java and Maven versions
5. Maven Central metadata populated (`name`, `description`, `url`, `licenses`, `developers`, `scm`) with MPL 2.0 licensing.
6. Root Maven wrapper scripts (`mvnw`, `mvnw.cmd`) generated and functional.

### Key Discoveries

- ENG-001 explicitly requires Maven Central metadata and MPL 2.0 in this ticket, not as a later publishing-only task (`ai/thoughts/tickets/eng-001-parent-pom.md:16`).
- Follow-on tickets (ENG-002/003) both depend on the root POM for module registration and reactor builds (`ai/thoughts/tickets/eng-002-starter-module.md:10-12`, `ai/thoughts/tickets/eng-003-sample-app.md:10-12`).
- Maven Wrapper docs recommend generating/updating scripts via `mvn wrapper:wrapper` and allow pinning Maven with `-Dmaven=<version>` (Context7: `/websites/maven_apache_tools_wrapper`).
- Spring Boot docs support importing `spring-boot-dependencies` in `dependencyManagement` when not inheriting from `spring-boot-starter-parent` (Context7: `/spring-projects/spring-boot/v3.5.9`).
- Spring AI docs for v1.1.2 confirm BOM import coordinates under `dependencyManagement` (Context7: `/spring-projects/spring-ai/v1.1.2`).

## What We're NOT Doing

- Creating module directories or module-level `pom.xml` files for `bifrost-spring-boot-starter` or `bifrost-sample` (covered by ENG-002 and ENG-003).
- Implementing Spring auto-configuration classes, imports files, or sample application code.
- Configuring release plugins (e.g., GPG/signing/deploy pipeline) beyond required Maven Central metadata fields.
- Publishing artifacts to Maven Central in this ticket.

## Implementation Approach

Use a minimal, deterministic bootstrap sequence:

1. Author a root parent POM first so all subsequent wrapper and validation commands run against a valid project descriptor.
2. Pin critical versions via properties to keep BOM and plugin upgrades controlled.
3. Enforce runtime/toolchain constraints early with Maven Enforcer to fail fast for unsupported Java/Maven versions.
4. Generate wrapper scripts after POM creation and confirm reproducible local invocation through `mvnw.cmd`.

## Phase 1: Create Root Aggregator Parent POM

### Overview

Introduce `pom.xml` as the project root aggregator and central dependency/plugin management source.

### Changes Required:

#### 1. Root Parent POM Definition
**File**: `pom.xml`

**Changes**:
- Add Maven project coordinates and packaging:
  - `groupId`: `com.lokiscale.bifrost`
  - `artifactId`: `bifrost-parent`
  - `version`: `0.1.0-SNAPSHOT`
  - `packaging`: `pom`
- Add core properties:
  - `maven.compiler.release=21`
  - `project.build.sourceEncoding=UTF-8`
  - `spring-boot.version=3.5.11`
  - `spring-ai.version=1.1.2`
  - plugin version properties for compiler/surefire/enforcer
- Add `dependencyManagement` BOM imports:
  - `org.springframework.boot:spring-boot-dependencies:${spring-boot.version}`
  - `org.springframework.ai:spring-ai-bom:${spring-ai.version}`
- Add `build > pluginManagement` entries:
  - `maven-compiler-plugin` configured to use `${maven.compiler.release}`
  - `maven-surefire-plugin` configured for JUnit Jupiter platform
  - `maven-enforcer-plugin` with `requireJavaVersion` and `requireMavenVersion` rules
- Add Maven Central metadata:
  - `<name>`, `<description>`, `<url>`
  - MPL 2.0 `<licenses>` block
  - `<developers>` and `<scm>` matching repository ownership

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>${spring-boot.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>${spring-ai.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### Success Criteria:

#### Automated Verification:
- [x] Root POM validates: `mvn -q -N validate`
- [x] Effective POM renders successfully: `mvn -q -N help:effective-pom -Doutput=target/effective-pom.xml`
- [x] Enforcer rules execute on validate phase with current toolchain: `mvn -q -N validate`
- [x] POM contains required BOM imports and plugin management sections.

#### Manual Verification:
- [ ] `pom.xml` visibly includes all required metadata (`name`, `description`, `url`, `licenses`, `developers`, `scm`).
- [ ] License metadata explicitly references MPL 2.0.
- [ ] No module declarations are added yet that would break builds before ENG-002/ENG-003 scaffolding.

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 2: Generate Maven Wrapper Scripts and Pin Maven Version

### Overview

Create root wrapper scripts and align wrapper properties with a pinned Maven runtime for reproducibility.

### Changes Required:

#### 1. Generate Wrapper Scripts
**Files**:
- `mvnw` (new)
- `mvnw.cmd` (new)
- `.mvn/wrapper/maven-wrapper.properties` (update if needed)

**Changes**:
- Run Maven Wrapper plugin from project root to create scripts.
- Pin wrapper distribution to Maven 3.9.11 (or chosen minimum that matches enforcer rules).
- Keep wrapper metadata consistent with generated script version.

```bash
mvn -N wrapper:wrapper -Dmaven=3.9.11
```

### Success Criteria:

#### Automated Verification:
- [x] Wrapper scripts exist at root: `mvnw`, `mvnw.cmd`
- [x] Wrapper command runs successfully: `mvnw.cmd -q -N validate`
- [x] Wrapper distribution URL is pinned to expected Maven version in `.mvn/wrapper/maven-wrapper.properties`
- [x] Wrapper can print Maven info: `mvnw.cmd -v`

#### Manual Verification:
- [ ] `mvnw.cmd` executes without requiring PATH-based Maven customization after initial setup.
- [ ] Team can use wrapper command as canonical build entrypoint for subsequent tickets.

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 3: Governance Hardening and Ticket Readiness Validation

### Overview

Verify that the parent build conventions are robust and ready for ENG-002/ENG-003 module onboarding.

### Changes Required:

#### 1. Validate Enforced Build Governance
**File**: `pom.xml`

**Changes**:
- Confirm enforcer rules are strict enough to fail early for unsupported toolchains.
- Ensure surefire/compiler plugin defaults are inherited cleanly by child modules.
- Confirm property naming is clear and reusable by module POMs.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <executions>
    <execution>
      <id>enforce-versions</id>
      <goals><goal>enforce</goal></goals>
      <configuration>
        <rules>
          <requireJavaVersion>
            <version>[21,)</version>
          </requireJavaVersion>
          <requireMavenVersion>
            <version>[3.9,)</version>
          </requireMavenVersion>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### Success Criteria:

#### Automated Verification:
- [x] Reactor root remains valid with wrapper: `mvnw.cmd -q -N validate`
- [x] Toolchain gating remains active through enforcer execution during validate phase.
- [x] Build is ready for child module introduction without refactoring parent structure.

#### Manual Verification:
- [ ] Parent POM structure is easy to extend for module-specific dependencies/plugins in ENG-002/ENG-003.
- [ ] Team agrees wrapper + parent standards are sufficient for Phase 1 implementation velocity.

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before moving to ENG-002.

---

## Testing Strategy

### Unit Tests:
- No code-level unit tests are introduced in this ticket.
- Validate that Surefire/JUnit Jupiter defaults are configured and inherited for future module tests.

### Integration Tests:
- Root-level build integration check via wrapper: `mvnw.cmd -q -N validate`
- Effective POM generation check: `mvnw.cmd -q -N help:effective-pom -Doutput=target/effective-pom.xml`

**Note**: A dedicated `/testing_plan` can expand this into failing-test-first strategy once module code exists.

### Manual Testing Steps:
1. Open `pom.xml` and confirm all ENG-001 acceptance fields are present.
2. Execute `mvnw.cmd -v` and verify wrapper-managed Maven is used.
3. Execute `mvnw.cmd -q -N validate` from repository root.
4. Confirm repository is ready for ENG-002 module addition without reworking parent conventions.

## Performance Considerations

- This ticket has negligible runtime impact; changes are build-time only.
- Enforcer checks add minimal validation overhead but reduce downstream integration failures.

## Migration Notes

- No data migration is required.
- If local developers already use global Maven, wrapper usage should be standardized in documentation/CI to avoid version drift.

## References

- Original ticket: `ai/thoughts/tickets/eng-001-parent-pom.md`
- Phase context: `ai/thoughts/phases/phase1.md`
- Follow-on dependencies:
  - `ai/thoughts/tickets/eng-002-starter-module.md`
  - `ai/thoughts/tickets/eng-003-sample-app.md`
- Maven Wrapper docs (Context7): `/websites/maven_apache_tools_wrapper`
- Spring Boot BOM import docs (Context7): `/spring-projects/spring-boot/v3.5.9`
- Spring AI BOM docs v1.1.2 (Context7): `/spring-projects/spring-ai/v1.1.2`
