# Ticket 003: Phase 1 - Sample App Execution

## Goal
Create the `bifrost-sample` demo application to validate the build and ensure the starter auto-configuration works.

## Description
To test our autoconfiguration and provide a runway for future phases, we need a standard Spring Boot application that imports our new starter.

## Acceptance Criteria
- [x] Create a new directory and `pom.xml` for `bifrost-sample`.
- [x] Add the module to the `<modules>` list in the root `pom.xml`.
- [x] Add dependencies in the sample POM:
  - `org.springframework.boot:spring-boot-starter-web` (or standard starter)
  - `bifrost-spring-boot-starter` (sibling project dependency)
- [x] Create a standard Spring Boot main class annotated with `@SpringBootApplication` (e.g., `SampleApplication.java`).
- [x] Create a basic `application.yml` or `application.properties` configuring the app name and a server port if needed.
- [x] Add a basic `@SpringBootTest` test class (`SampleApplicationTests`) that verifies the `ApplicationContext` loads without errors.
- [x] Verify that starting the application loads the placeholder `BifrostAutoConfiguration`.

## Implementation Details
The starter dependency should be declared without a version if it's managed by the reactor/parent, or using `${project.version}`. This test app acts as our primary validation checkpoint for Phase 1.
