# Phase 1 - Spring Boot Starter Foundation

## Goal
Establish the repository, Maven build, and core spring-boot-starter module for Bifrost using OpenJDK 21.

## Primary Outcomes
- A parent Maven POM.
- The `bifrost-spring-boot-starter` library module is created.
- A `bifrost-sample` demo app is created.
- The build is reproducible on OpenJDK 21 featuring Spring Boot 3.4+.

## Scope
- Centralize dependency and plugin versions in the parent POM.
- Configure Java 21 compilation.
- Add Maven Wrapper.
- Import `spring-boot-dependencies` BOM and Spring AI BOM.
- Define package naming conventions and auto-configuration structure.

## Detailed Tasks
### 1. Parent build setup
- Create root `pom.xml` as the aggregator.
- Add `bifrost-spring-boot-starter` and `bifrost-sample` projects.
- Set Java release to 21 to take advantage of Virtual Threads.
- Configure dependency management for Spring Boot and Spring AI.

### 2. Module scaffolding
- Set up `spring.factories` or `org.springframework.boot.autoconfigure.AutoConfiguration.imports` for the starter.
- Add placeholder `BifrostAutoConfiguration` class.

### 3. Build governance & Testing
- Add maven plugins (surefire, enforcer).
- Set up JUnit Jupiter.

## Deliverables
- Root parent `pom.xml`
- `bifrost-spring-boot-starter` and `bifrost-sample` modules.
- Initial Spring autoconfiguration skeleton.

## Exit Criteria
- `./mvnw package` succeeds on OpenJDK 21.
- Sample app boots cleanly including the auto-configuration from the starter.
