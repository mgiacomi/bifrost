# Ticket: First-Class Attachments for YAML Skill Execution

## Summary

Add first-class attachment support to Bifrost so YAML skills can receive files such as images, PDFs, audio, or other binary artifacts as mission input and pass them to the underlying LLM as actual model attachments instead of stringified JSON.

This unlocks pure skill-based form parsing. For example, the sample app should be able to define a YAML skill that parses `bifrost-sample/src/main/resources/forms/feedstock-p1.jpg` directly with a vision-capable model, without a Java parsing service wrapping the OpenAI Responses API.

## Motivation

The current feedstock sample has a useful Java-backed implementation:

- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java`
- `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser.yml`

That example should remain because it demonstrates deterministic Java-backed skills and an explicit OpenAI Responses integration.

However, Bifrost should also support the more idiomatic agentic version:

1. A controller, caller, or upstream skill supplies an image attachment.
2. A YAML skill describes the extraction task and output schema.
3. Bifrost sends the image bytes to the model as multimodal input.
4. The skill returns schema-validated JSON.

Today, step 3 cannot happen through the YAML skill path. Mission input is converted to text and sent via `.user(String)`, so a filename, `ref://...` URI, or data URL becomes text context rather than model-visible image content.

## Current Behavior

### YAML mission input is stringified

`MissionInputMessageFormatter` converts mission input to pretty-printed JSON text:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/MissionInputMessageFormatter.java:36`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/MissionInputMessageFormatter.java:47`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/MissionInputMessageFormatter.java:79`

This is fine for structured text fields, but not enough for binary attachments.

### Model calls use text-only user messages

The single-shot YAML execution path builds a text user message and calls:

- `DefaultMissionExecutionEngine.java:152` -> `.user(userMessage)`

The step-loop YAML execution path does the same:

- `StepLoopMissionExecutionEngine.java:397` builds the step user message
- `StepLoopMissionExecutionEngine.java:540` -> `.user(stepUserMessage)`

Planning is also text-only:

- `DefaultPlanningService.java:416` builds `planningUserMessage`
- `DefaultPlanningService.java:440` -> `.user(planningUserMessage)`

### Ref support exists, but only helps Java method targets

The VFS/ref pieces already provide a useful foundation:

- `DefaultRefResolver` recognizes strict `ref://...` strings and resolves them to `Resource`.
- `RefResolver.resolveArguments(...)` recursively resolves nested maps/lists.
- `SessionLocalVirtualFileSystem` scopes refs under the current session root.

Relevant files:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/RefResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java`

Java-backed skill methods already understand ref-capable arguments:

- `SkillMethodBeanPostProcessor.java:210` through `SkillMethodBeanPostProcessor.java:222` adds ref-friendly schema descriptions.
- `SkillMethodBeanPostProcessor.java:286` through `SkillMethodBeanPostProcessor.java:302` binds `Resource`, `byte[]`, `InputStream`, and resource-backed strings.
- `SkillInputValidator.java:245` through `SkillInputValidator.java:253` accepts runtime ref values for ref-capable string schemas.

But unmapped YAML skills are routed back into `ExecutionCoordinator` before ref resolution is applied for model input:

- `CapabilityExecutionRouter.java:69` detects unmapped YAML skills.
- `CapabilityExecutionRouter.java:74` passes `normalizedInput` to `ExecutionCoordinator`.
- `CapabilityExecutionRouter.java:82` only calls `refResolver.resolveArguments(...)` for mapped/non-LLM capability invocations.

So Java methods can consume refs as resources, but LLM-backed YAML skills cannot yet consume refs as media attachments.

## Goals

- Allow YAML skills to declare attachment inputs.
- Allow callers to pass attachments by session-scoped `ref://...` values and already-materialized Spring `Resource` values.
- Resolve attachments before LLM execution.
- Pass image attachments to Spring AI as model media, not JSON text.
- Keep structured mission input visible to the model without inlining large binary payloads into text.
- Preserve useful execution traces while avoiding giant base64 blobs in trace records.
- Support both single-shot and step-loop YAML execution.
- Make the feedstock sample possible as a pure YAML skill using a vision-capable model.

## Non-Goals

- Do not remove the existing Java-backed feedstock parser example.
- Do not require every provider/model to support every attachment type.
- Do not implement full PDF rendering in Bifrost as part of the first pass. PDF-to-image conversion can be a later feature or a Java skill.
- Do not store raw attachment bytes in execution trace payloads.
- Do not make planners inspect large attachments unless there is a deliberate mode for that.
- Do not support arbitrary direct `file:` or `classpath:` string sources in the first pass. Callers should stage files into the session VFS and pass `ref://...`, or provide a `Resource`.
- Do not expose `InputStream` as a first-pass YAML attachment input type unless Spring AI/provider APIs are verified to preserve streaming behavior end-to-end.
- Do not implement attachment passing between step-loop tools/skills as part of the first implementation.

## Proposed Design

### 1. Add an attachment model

Introduce a small internal value object, for example:

```java
public record BifrostAttachment(
        String name,
        String contentType,
        Resource resource,
        AttachmentMediaType mediaType,
        String source,
        Long sizeBytes,
        Map<String, Object> metadata) {
}
```

Suggested `AttachmentMediaType` values:

- `IMAGE`
- `PDF`
- `AUDIO`
- `VIDEO`
- `FILE`

The pipeline should be generic enough to carry provider-supported media types as Spring AI/provider APIs allow. The first sample and primary tests can focus on images.

### 2. Extend YAML input schema with a Bifrost-native attachment type

Current schema validation accepts a constrained JSON-schema subset. For attachments, add one Bifrost-native schema type for input schemas, for example:

```yaml
input_schema:
  type: object
  required: [image]
  additionalProperties: false
  properties:
    image:
      type: attachment
      media_type: image
      allowed_content_types:
        - image/jpeg
        - image/png
      description: Feedstock weighmaster certificate image.
```

Implementation notes:

- `attachment` is intentionally not a JSON Schema primitive. It is a Bifrost input contract type that means the runtime value is an attachment source, not inline text.
- For `type: attachment`, `media_type` is the broad model-facing category and `allowed_content_types` is the precise MIME allowlist for that field.
- The first-pass accepted runtime values should be strict `ref://...` strings and Spring `Resource` values. `byte[]` can be considered only if needed for Java caller ergonomics and should be documented as in-memory.
- `InputStream` should be deferred unless the Spring AI/provider path is verified to stream without buffering and with a clear lifecycle.
- `YamlSkillCatalog` currently rejects unsupported schema fields. It will need to allow and validate `type: attachment`, `media_type`, and `allowed_content_types` for `input_schema`.
- `SkillInputSchemaNode` should carry attachment metadata, similar to the existing `runtimeRefCapable` flag.
- `SkillInputContractResolver` should parse and render these markers.
- `SkillInputValidator` should validate attachment-marked fields against the supported runtime source types and declared content-type policy.

### 3. Add a mission input materializer

Create a component responsible for splitting mission input into:

- text-safe canonical input for prompts and traces
- model attachments for the user message

Possible name:

```java
MissionInputMaterializer
```

Responsibilities:

- Walk the validated input schema and normalized mission input.
- Resolve attachment fields through `RefResolver` / `VirtualFileSystem`.
- Determine content type from declared `allowed_content_types`, explicit resource metadata if available, `Resource#getFilename`, or bounded sniffing.
- Replace attachment values in the text-safe mission input with descriptors, not bytes.

Example text-safe JSON:

```json
{
  "image": {
    "attachment": true,
    "name": "feedstock-p1.jpg",
    "contentType": "image/jpeg",
    "mediaType": "IMAGE",
    "source": "ref://forms/feedstock-p1.jpg"
  }
}
```

### 4. Build multimodal user messages

Create an abstraction that represents a rendered model user turn:

```java
public record RenderedMissionInput(
        String userText,
        List<BifrostAttachment> attachments,
        Map<String, Object> traceSafeInput) {
}
```

Then update model calls to use the Spring AI multimodal user-message API when attachments are present.

Affected call sites:

- `DefaultMissionExecutionEngine` single-shot mission call.
- `StepLoopMissionExecutionEngine.callModelForStep(...)`.
- Possibly `DefaultPlanningService`, but see planning policy below.

The code should avoid `.user(String)` when attachments exist. It should use the Spring AI `UserMessage` / media path supported by the project’s Spring AI version.

### 5. Planning policy

Default behavior should be:

- Planning receives text-safe attachment descriptors only.
- Execution receives descriptors plus the actual media attachment.

Reasoning:

- Planning usually needs to know that an image exists, its name, and type.
- Sending large media to the planner can be expensive and can encourage the planner to parse content before execution.
- The step-loop execution model should receive media when it needs to produce the final answer or call an attachment-aware sub-skill.

Open design question:

- Add YAML flag such as `planning_attachments: true` only if a use case needs planner-visible media.

### 6. Provider/model capability checks

Add validation or clear runtime errors when a skill declares image attachments but the configured model/provider cannot support them.

Possible first-pass behavior:

- Allow the model call to fail naturally, but wrap the exception with a Bifrost-specific message:
  - skill name
  - provider
  - provider model
  - attachment kind/content type
  - hint to use a vision-capable model

Better second-pass behavior:

- Add provider capability metadata to `bifrost.models.*`, for example:

```yaml
bifrost:
  models:
    openai-vision:
      provider: openai
      provider-model: gpt-5-mini
      capabilities: [text, image]
```

### 7. Trace and usage behavior

Execution trace should include:

- attachment count
- names
- content types
- sizes if known
- source ref if applicable

Execution trace should not include:

- raw bytes
- full base64 data URLs

Affected trace points:

- `DefaultMissionExecutionEngine.buildMissionSentPayload(...)`
- `StepLoopMissionExecutionEngine.callModelForStep(...)`
- `DefaultPlanningService.requestPlanAttempt(...)`

Usage extraction can continue using text prompts and response content. Attachment token accounting can be left provider-native in the first pass unless Spring AI exposes multimodal token usage reliably.

## Sample Target After This Feature

Add a second sample skill, keeping the existing Java-backed example intact:

```yaml
name: feedstockTicketParserBySkill
description: >
  Parses a feedstock weighmaster certificate image directly through the skill's
  configured vision-capable LLM. No Java parser backs this skill.
model: openai-vision
planning_mode: false
input_schema:
  type: object
  required: [image]
  additionalProperties: false
  properties:
    image:
      type: attachment
      media_type: image
      allowed_content_types:
        - image/jpeg
      description: Feedstock weighmaster certificate image.
output_schema:
  type: object
  properties:
    zhcbz_tag:
      type: string
    ticket_no:
      type: string
    datetime_in:
      type: string
    datetime_out:
      type: string
    gross_weight:
      type: number
    tare_weight:
      type: number
    net_weight:
      type: number
    driver_name:
      type: string
    truck_no:
      type: string
    carrier_name:
      type: string
    notes:
      type: string
    cumulative_total:
      type: number
    total_tons:
      type: number
  required:
    - zhcbz_tag
    - ticket_no
    - datetime_in
    - datetime_out
    - gross_weight
    - tare_weight
    - net_weight
    - driver_name
    - truck_no
    - carrier_name
    - notes
    - cumulative_total
    - total_tons
  additionalProperties: false
output_schema_max_retries: 2
```

The sample controller endpoint could then do minimal attachment staging, not parsing:

```java
skillTemplate.invoke("feedstockTicketParserBySkill",
        Map.of("image", "ref://forms/feedstock-p1.jpg"),
        holder::set);
```

If classpath resources are preferred for samples, add an explicit safe resource resolver or sample helper that stages the classpath image into the session VFS and returns a `ref://...`.

## Acceptance Criteria

- A YAML skill can declare an image attachment input using a documented manifest extension.
- `SkillTemplate.invoke(...)` accepts a `ref://...` value for that attachment input.
- Bifrost resolves the ref to a `Resource` scoped to the current session.
- The LLM execution path sends image bytes to Spring AI as media, not as text JSON.
- The single-shot execution engine supports attachment-aware user messages.
- The step-loop execution engine supports attachment-aware user messages for execution steps and final response generation.
- Planning remains text-descriptor-only by default.
- Traces show attachment metadata but not raw bytes/base64.
- Invalid refs produce clear validation/runtime errors.
- Non-image files passed to an image attachment field produce a clear error.
- A provider/model that cannot handle the attachment produces a clear error with skill/model context.
- Add tests covering:
  - manifest parsing for attachment markers
  - input validation for attachment fields
  - ref resolution into attachment descriptors
  - single-shot model call receives media
  - step-loop model call receives media
  - trace redaction of attachment bytes
  - sample skill registration

## Suggested Implementation Steps

0. Complete prework:
   - Upgrade Spring AI from `1.1.2` to the latest stable `1.1.x` patch line.
   - Run the full Bifrost test suite after the upgrade.
   - Add a small implementation spike/test for the actual Spring AI multimodal request shape, using `UserMessage`/`Media` and `Resource`-backed data.
   - Split YAML input schema modeling from output schema modeling before adding `type: attachment`.
1. Add `BifrostAttachment`, `AttachmentMediaType`, and `RenderedMissionInput` types.
2. Extend the dedicated input schema manifest node to support `type: attachment`, `media_type`, and `allowed_content_types`. Keep output schema manifests strict and do not teach them about input-only attachment types.
3. Extend `YamlSkillCatalog` validation to allow and validate the Bifrost-native attachment schema fields on `input_schema`.
4. Extend `SkillInputSchemaNode` and `SkillInputContractResolver` to preserve attachment metadata.
5. Extend `SkillInputValidator` to validate attachment-marked fields.
6. Add `MissionInputMaterializer`.
7. Update `DefaultMissionExecutionEngine` to call the materializer and send multimodal user messages.
8. Update `StepLoopMissionExecutionEngine` similarly.
9. Decide whether `DefaultPlanningService` should use descriptors only or optionally include media.
10. Add trace-safe payload builders for attachment metadata.
11. Add unit and integration tests with fake ChatClient / fake request capture.
12. Add `feedstockTicketParserBySkill` sample once the library feature is in place.

## Design Decisions

- Source schemes: first pass supports strict session-scoped `ref://...` string values and Spring `Resource` values. Direct `file:` and `classpath:` string sources are outside the first-pass core path.
- Input contract scope: attachment support must be declared in `input_schema`. Generic/untyped mission input should not be scanned for attachments.
- Input/output schema modeling: introduce a dedicated input schema manifest/model before attachment implementation. `type: attachment` is a Bifrost input contract extension, not an output JSON Schema type.
- YAML syntax: use a Bifrost-native schema type:

  ```yaml
  document:
    type: attachment
    media_type: image
    allowed_content_types:
      - image/jpeg
  ```

- `media_type` is a broad model-facing category such as `image`, `audio`, `video`, `pdf`, or `file`. `allowed_content_types` is the precise MIME policy for that field.
- Bifrost should not be a permanent gatekeeper for all possible provider-supported file types. The attachment pipeline should be generic where Spring AI/provider APIs allow it, with declared content-type policy and clear provider-error wrapping.
- Do not add a global content-type allowlist in the first pass. Developers should declare per-field MIME policy in YAML skill schemas.
- A Bifrost-level maximum attachment size remains useful even without global content-type limits, because it protects runtime memory/storage and gives clearer errors before provider calls.
- Traces should store trace-safe attachment descriptors only: field name/path, filename, content type, media type, source ref, size when known, and a stable digest such as SHA-256 when practical. Raw bytes and full base64 data URLs must not be stored in trace payloads.
- Do not copy attachment files next to execution traces by default. If audit retention is needed later, add an explicit opt-in artifact store that records descriptor IDs/digests in trace metadata.
- PDF rendering is not part of the first pass. Direct PDF/file forwarding can be supported later when the provider/Spring AI path supports it; PDF-to-image conversion remains a separate feature or Java skill.
- Step-loop execution should receive original mission attachments when needed, but generated attachment passing between tools/skills is a follow-up feature.
- Java caller ergonomics: leave `byte[]` out of the first public YAML attachment input shape. Start with `ref://...` and Spring `Resource`.

## Resolved Open Questions

- First-pass Java caller ergonomics should be limited to strict `ref://...` values and Spring `Resource`; leave `byte[]` for a later concrete caller use case.
- Do not add global content-type limits. Use skill-declared `allowed_content_types` for MIME policy.
- Keep a configurable Bifrost-level maximum attachment size, but choose the exact default during implementation after checking sample image sizes and provider limits.
- First-pass content-type detection should use declared field policy plus resource metadata/filename. Bounded byte sniffing can be added if filename/resource metadata is insufficient or too fragile in practice.
- Upgrade Spring AI to the latest stable `1.1.x` patch line before implementing attachments, then verify the real `UserMessage`/`Media` request shape with tests.

## Notes From Code Research

- The VFS and ref resolver are a strong starting point; they already enforce session scoping and nested argument traversal.
- The Java `@SkillMethod` path already has a notion of ref-capable runtime inputs, but it is tied to Java parameter types and does not yet translate to model media.
- The main architectural gap is not file loading. It is preserving attachment identity through validation, prompt rendering, trace redaction, and the Spring AI chat request boundary.
- The current output schema validator does not support nullable union types such as `["string", "null"]`; that is separate from attachment support. The first pure YAML feedstock skill may need non-null schema fields unless nullable schema support is added separately.
