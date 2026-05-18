---
date: 2026-05-17 17:55:43 PDT
researcher: GPT-5
git_commit: a916d48c00f9062218e11eec1f821892bbbe3763
branch: main
repository: bifrost
topic: "First-Class Attachments for YAML Skill Execution"
tags: [research, codebase, yaml-skills, attachments, mission-input, vfs, spring-ai]
status: complete
last_updated: 2026-05-18
last_updated_by: GPT-5
last_updated_note: "Added Spring AI upgrade prework and resolved attachment implementation questions"
---

# Research: First-Class Attachments for YAML Skill Execution

**Date**: 2026-05-17 17:55:43 PDT
**Researcher**: GPT-5
**Git Commit**: a916d48c00f9062218e11eec1f821892bbbe3763
**Branch**: main
**Repository**: bifrost

## Research Question

Use `ai/commands/1_research_codebase.md` to perform codebase research for `ai/thoughts/tickets/eng-first-class-attachments-for-yaml-skills.md`.

## Summary

The current YAML skill execution path accepts structured mission input as maps, validates it against an optional YAML `input_schema`, and renders it into text through `MissionInputMessageFormatter`. The single-shot engine, step-loop engine, and planning service all submit string user messages to Spring AI with `.user(String)`. No first-class attachment value object, attachment schema marker, mission input materializer, or multimodal user-message construction exists in the main runtime code today.

The repository does already have a VFS/ref foundation. Strict `ref://...` strings can resolve to Spring `Resource` instances, and Java-backed `@SkillMethod` capabilities can bind those resources as `Resource`, `byte[]`, `InputStream`, or resource-backed strings. That ref materialization is applied at the deterministic capability invocation boundary, while unmapped YAML skills are routed into the `ExecutionCoordinator` with their normalized input still as ordinary values.

The feedstock sample currently demonstrates the vision use case through a Java service that reads a classpath JPG, encodes it as a data URL, and calls the OpenAI Responses API directly. The YAML manifest for that sample is mapped to the Java method and has no image input schema.

## Detailed Findings

### Mission Input Text Rendering

`MissionInputMessageFormatter` is the shared formatter for mission input text. `buildMissionContext(...)` appends `Canonical mission input:` plus pretty-printed JSON when mission input is present (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/MissionInputMessageFormatter.java:18`). `buildUserMessage(...)` produces a text prompt with the mission objective and the pretty-printed mission input (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/MissionInputMessageFormatter.java:36`). The pretty-printer serializes the input map with Jackson (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/MissionInputMessageFormatter.java:79`).

There is no attachment-aware branch in this formatter. Any filename, `ref://...` URI, data URL, `Resource`, or byte-like value that reaches this formatter is treated as data inside the canonical mission input text.

### Single-Shot YAML Execution

`DefaultMissionExecutionEngine.executeMission(...)` builds `userMessage` once from the objective and mission input (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:111`). The model trace input and sent payload both contain string fields for `system` and `user` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:140`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:149`). The actual Spring AI request is built with `.system(executionPrompt)` and `.user(userMessage)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:150`).

The trace sent-payload helper stores the execution prompt, user message, tool count, and tool names (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:332`). It has no attachment metadata fields.

### Step-Loop YAML Execution

`StepLoopMissionExecutionEngine` passes the original mission input into each step prompt build (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:386`). The step user message is also built from the plan, objective, and mission input (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:397`).

`StepPromptBuilder.buildStepUserMessage(...)` delegates to `MissionInputMessageFormatter.buildUserMessage(...)`, so canonical mission input remains text JSON in the step-loop path (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepPromptBuilder.java:226`).

`callModelForStep(...)` records string `system` and `user` trace inputs, builds a sent payload with the same strings, and calls Spring AI with `.user(stepUserMessage)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:530`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:537`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:538`).

### Planning

`DefaultPlanningService.requestPlanAttempt(...)` builds the planning user message from the same mission-input formatter (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:415`). Its trace input and sent payload are string `system` and `user` values (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:426`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:435`). The Spring AI call uses `.user(planningUserMessage)` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:438`).

### YAML Skill Routing and Ref Resolution

`CapabilityExecutionRouter.execute(...)` validates input, stores the normalized map, and checks for an unmapped YAML skill (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:60`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:67`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:69`). For unmapped YAML skills, it calls `ExecutionCoordinator.execute(...)` with `normalizedInput` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:75`). For mapped or non-LLM capability invocations, it resolves refs before invoking the capability (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:85`).

`ExecutionCoordinator.execute(...)` stores mission input in the root mission frame parameters when present, creates the appropriate chat client, builds tool callbacks, and passes the same `missionInput` into the selected mission execution engine (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:85`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:97`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/ExecutionCoordinator.java:109`).

### VFS and Ref Infrastructure

`DefaultRefResolver` recognizes only strings that fully match `^ref://\S+$` and resolves them through the configured `VirtualFileSystem` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java:9`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java:19`).

`RefResolver.resolveArguments(...)` walks maps, lists, and arrays recursively, resolving ref-like leaves and returning unmodifiable containers (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/RefResolver.java:16`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/RefResolver.java:24`).

`SessionLocalVirtualFileSystem.resolve(...)` scopes refs under a per-session root, rejects paths that escape that root, checks existence, and returns a `FileSystemResource` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:20`). The session root itself is derived from the VFS root and `session.getSessionId()` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:41`).

### Java-Backed Skill Ref Semantics

`SkillMethodBeanPostProcessor` rewrites ref-capable Java parameter schema nodes as string schema nodes with `x-bifrost-runtime-ref-capable: true` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:200`). It describes byte arrays, `Resource`, and `InputStream` values as `ref://`-friendly input (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:208`).

At invocation time, Java method parameters can receive resolved resources directly. The binder handles `Resource`, `byte[]`, `InputStream`, and strings backed by `Resource` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:286`). Nested typed values are also materialized recursively for maps, collections, arrays, and records/POJOs (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/SkillMethodBeanPostProcessor.java:315`).

`SkillInputValidator` accepts runtime ref values only when the schema is a string and `runtimeRefCapable` is true (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputValidator.java:245`). Accepted runtime values are `byte[]`, `Resource`, and `InputStream` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputValidator.java:251`). Ordinary `ref://...` strings still satisfy string validation as strings; they are not materialized by the validator.

### YAML Input Schema Model

`YamlSkillManifest` has top-level `input_schema` and `output_schema` fields, both typed as `OutputSchemaManifest` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:37`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:40`). `OutputSchemaManifest` supports `type`, `properties`, `required`, `additionalProperties`, `items`, string enum values, `description`, and `format` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:306`).

The manifest classes use `@JsonIgnoreProperties(ignoreUnknown = false)`, so unknown YAML fields fail deserialization (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:13`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:306`). The catalog validates input schema and output schema through the same schema validator (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:141`, `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:274`). Supported schema types are object, array, string, number, integer, and boolean (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:41`).

`SkillInputSchemaNode` currently carries `type`, object/array structure, enum values, description, format, and `runtimeRefCapable`; it has no attachment kind, attachment flag, content type, or source metadata field (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputSchemaNode.java:7`).

`SkillInputContractResolver.fromManifest(...)` copies the manifest schema into `SkillInputSchemaNode` and sets `runtimeRefCapable` to false for explicit YAML manifest fields (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java:93`). `fromJsonNode(...)` can read `x-bifrost-runtime-ref-capable` from Java-reflected JSON schemas (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java:237`). `toJsonSchemaNode(...)` emits `x-bifrost-runtime-ref-capable` when present (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java:303`).

### Spring AI Dependencies and Current Request Surface

The parent POM pins Spring AI to `1.1.2` (`pom.xml:50`). The starter depends on Spring AI model, chat client, and provider modules (`bifrost-spring-boot-starter/pom.xml:23`).

The production code search found no `BifrostAttachment`, `AttachmentMediaType`, `RenderedMissionInput`, `UserMessage`, `Media`, or `.media(...)` use in `src/main/java`. Test fakes implement Spring AI `user(Resource)` overloads because they implement the request-spec interface, but runtime model calls use only string user messages.

### Feedstock Sample

The feedstock YAML skill is `feedstockTicketParser`, uses model `granite4-tiny`, disables planning, and maps to the Java method `feedstockFormExtractionService#extractSampleFeedstockTicket` (`bifrost-sample/src/main/resources/skills/feedstock_ticket_parser.yml:1`).

`FeedstockFormExtractionService.extractSampleFeedstockTicket()` loads `classpath:/forms/feedstock-p1.jpg`, reads the bytes, builds a `data:` URL using a local MIME helper, and places that data URL in an OpenAI Responses `input_image` item (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java:97`, `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java:107`, `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java:119`). It sends the request with `java.net.http.HttpClient` to `https://api.openai.com/v1/responses` (`bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java:137`).

## Code References

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/MissionInputMessageFormatter.java:36` - builds text user messages with pretty-printed canonical mission input.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:150` - single-shot model call uses `.user(userMessage)`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:538` - step-loop model call uses `.user(stepUserMessage)`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:438` - planning model call uses `.user(planningUserMessage)`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:69` - unmapped YAML skills are routed back to `ExecutionCoordinator`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:85` - ref resolution is applied before mapped/non-LLM capability invocation.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java:9` - strict `ref://...` matching.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:20` - session-scoped ref resolution to `Resource`.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputSchemaNode.java:7` - current input schema node fields.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputValidator.java:245` - runtime ref values accepted only for ref-capable string schema nodes.
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:306` - schema manifest fields currently accepted by YAML parsing.
- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java:119` - sample Java service constructs multimodal OpenAI Responses content manually.

## Architecture Documentation

The current architecture separates explicit YAML skill definitions from runtime execution. The catalog loads YAML resources, validates manifest shape, resolves model configuration, and creates `YamlSkillDefinition` objects. Capability registration derives a `SkillInputContract` from the explicit YAML input schema, an inherited mapped Java contract, or a generic object contract.

At invocation time, `SkillTemplate` validates against the capability input contract and runs the capability inside a new `BifrostSession` (`bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skillapi/DefaultSkillTemplate.java:67`). `CapabilityExecutionRouter` validates again at the execution boundary, routes unmapped YAML skills into the coordinator, and invokes mapped capabilities after resolving refs. `ExecutionCoordinator` chooses the single-shot engine or step-loop engine based on explicit planning mode.

The VFS/ref path is session-scoped and Spring `Resource` based. It is currently connected to deterministic Java capability invocation and Java parameter binding. The LLM-backed YAML path preserves mission input as normalized map data and renders it as prompt text at the model-call boundary.

Execution traces are built around model-call frames with payloads that include string prompts and string responses. Current model-call sent payloads do not have an attachment metadata structure.

## Historical Context (from ai/thoughts/)

- `ai/thoughts/tickets/eng-first-class-attachments-for-yaml-skills.md` - Captures the attachment feature motivation, current text-only behavior, VFS/ref foundation, proposed attachment model, materializer, multimodal request path, trace policy, and sample target.
- `ai/thoughts/phases/phase4.md:22` - Establishes a stable VFS abstraction using session-scoped `FileSystemResource` storage.
- `ai/thoughts/phases/phase4.md:45` - Records the goal that `ref://` payloads convert to `InputStream` through Spring abstractions.
- `ai/thoughts/phases/phase7.md:20` - Frames input contracts as a cross-cutting design concern across orchestration, Java callers, mapped YAML skills, future validation, and trace inspection.
- `ai/thoughts/phases/phase7.md:247` - Lists validation and trace integration for explicit input contracts as part of the input-contract phase.

## Related Research

No existing files were present in `ai/thoughts/research/` at the time of this research.

## Open Questions

The ticket itself contains open design questions about allowed source schemes, whether attachment support should be limited to `input_schema` fields, attachment metadata syntax, size limits, PDF handling, and whether step-loop tools should pass attachments between skills. The live codebase does not currently contain answers to those questions.

## Follow-up Research 2026-05-17 18:48:43 PDT

After discussion, the open design questions now have a clearer first-pass direction recorded in `ai/thoughts/tickets/eng-first-class-attachments-for-yaml-skills.md`.

Settled first-pass decisions:

- Attachment sources should start with strict session-scoped `ref://...` values and Spring `Resource` values.
- Direct `file:` and `classpath:` string sources should stay outside the core first-pass path. Callers can stage those resources into the session VFS and pass a logical `ref://...`.
- `InputStream` should be left out of the first public attachment input shape unless Spring AI/provider APIs are verified to stream end-to-end without buffering and with clear lifecycle semantics.
- Attachment support should be declared in `input_schema`; generic mission input should not be scanned for implicit attachments.
- The preferred YAML syntax is Bifrost-native:

  ```yaml
  document:
    type: attachment
    media_type: image
    allowed_content_types:
      - image/jpeg
  ```

- `type: attachment` means the input value is an attachment source, not inline text. Bifrost can compile that into a runtime contract accepting `ref://...` and `Resource`.
- `media_type` is the broad model-facing category, such as `image`, `audio`, `video`, `pdf`, or `file`.
- `allowed_content_types` is the precise MIME policy for that field.
- Bifrost should validate framework-level safety rules and declared field constraints, but should avoid becoming a permanent hardcoded gatekeeper for every provider-supported file type.
- The first pass should include Bifrost-level size limits so failures can be clear before the provider call.
- PDF rendering and generated attachment passing between step-loop tools/skills are follow-up features, not part of the first implementation.

Remaining implementation questions:

- Whether first-pass Java caller ergonomics should include `byte[]` in addition to `ref://...` and `Resource`.
- What default global size limit should ship with Bifrost.
- Whether first-pass content-type validation should rely on resource metadata and filenames, or also include bounded content sniffing.

## Follow-up Research 2026-05-18

After further review, the remaining questions above are now resolved for the first implementation slice:

- Upgrade Spring AI before implementing attachments. The project was pinned to Spring AI `1.1.2`; Spring AI `1.1.6` is the current stable `1.1.x` patch release as of 2026-05-18.
- Stay on the stable `1.1.x` line. Do not jump to Spring AI `2.0.0-M*` for this feature.
- Current Spring AI multimodality docs describe `UserMessage` text content plus optional media, with `Media` carrying raw content as a Spring `Resource` or a URI. Current Javadocs expose `UserMessage.Builder.media(...)`, so the Bifrost implementation should target that request shape.
- Add a small spike/test during implementation that captures the concrete `UserMessage`/`Media` payload generated by Bifrost for a `Resource` attachment.
- Split YAML input schema modeling from output schema modeling before adding `type: attachment`. Attachment is a Bifrost input contract extension, not an output schema type.
- First-pass YAML attachment input values should be strict `ref://...` strings and Spring `Resource` values. Leave `byte[]` out until a concrete caller use case requires it.
- Do not add a global content-type allowlist. Skill authors should declare per-field `allowed_content_types`.
- Keep a configurable Bifrost-level maximum attachment size. This is a runtime safety guard, not a MIME policy.
- First-pass content-type detection should rely on declared field policy plus resource metadata/filename. Bounded sniffing can be added later if needed.
- Traces should include descriptor metadata only: field path, name, content type, media type, source ref, size when known, and preferably SHA-256. They must not include raw bytes or base64 payloads.
- Do not copy attachment files next to traces by default. If audit retention becomes necessary, add an explicit opt-in artifact store and trace only artifact IDs/digests.
