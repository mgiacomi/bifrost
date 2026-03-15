# Ticket 001: Phase 1 - Project Initialization and Parent POM

## Goal
Establish the root repository structure, Maven wrapper, and the aggregator parent POM for the Bifröst project.

## Description
This ticket covers the foundational build setup required to support the Spring Boot Starter and the Sample Application. We will be using Java 21, Spring Boot 3.5.11, and Spring AI 1.1.2. The root POM will act as an aggregator and centralize dependency management under the `com.lokiscale.bifrost` group ID. We will also include Maven Central publishing metadata.

## Acceptance Criteria
- [ ] Maven wrapper (`mvnw` and `mvnw.cmd`) is generated in the root directory.
- [ ] Root `pom.xml` is created with `<packaging>pom</packaging>` and `<groupId>com.lokiscale.bifrost</groupId>`.
- [ ] Java 21 configuration (`<maven.compiler.release>21</maven.compiler.release>`) is established.
- [ ] `<dependencyManagement>` section includes:
  - `spring-boot-dependencies` (version 3.5.11)
  - `spring-ai-bom` (version 1.1.2)
- [ ] Maven Central publishing details (e.g. `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, `<scm>`) are included.  We will use MPL 2.0
- [ ] Core Maven plugins configured in `<build><pluginManagement>`:
  - `maven-compiler-plugin`
  - `maven-surefire-plugin` (configured for JUnit Jupiter)
  - `maven-enforcer-plugin` (to enforce Maven & Java versions)
- [ ] Basic project properties (encoding, versions) are set.

## Implementation Details
Ensure we use `maven-enforcer-plugin` to guarantee that builds fail early if an older Java version or Maven version is used.
