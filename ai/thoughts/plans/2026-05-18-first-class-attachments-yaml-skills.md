# First-Class Attachments for YAML Skills Implementation Plan

## Overview

Add first-class attachment support to YAML skill execution so callers can pass session-scoped `ref://...` values or Spring `Resource` values into declared attachment fields, and Bifrost sends those resources to Spring AI as multimodal user-message media instead of JSON text.

This plan keeps the current Java-backed feedstock parser intact while adding a pure YAML, vision-capable path for the same sample image.

## Current State Analysis

YAML skill mission input is currently validated as structured data and rendered into prompt text. `MissionInputMessageFormatter.buildUserMessage(...)` pretty-prints the whole input map as canonical JSON, so an image ref remains text in the prompt instead of becoming model media.

The core ref and VFS foundation already exists. Strict `ref://...` strings resolve through the session-local VFS, and Java-backed skill methods can receive `Resource`, `byte[]`, and `InputStream` values after ref resolution. That resolution is only applied to mapped/deterministic capability invocations today, not to unmapped YAML skill model input.

The parent POM already uses Spring AI `1.1.6`, so the ticket's Spring AI patch-line upgrade prework appears complete.

## Desired End State

A YAML skill can declare an attachment input in `input_schema`, for example:

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
```

At runtime, `SkillTemplate.invoke(...)` can pass `Map.of("image", "ref://forms/feedstock-p1.jpg")` or `Map.of("image", resource)`. Bifrost validates the field, resolves the ref within the current session, builds trace-safe attachment descriptors, and sends the actual image `Resource` through Spring AI `UserMessage` media. Planning receives only the descriptor text by default.

### Key Discoveries

- `MissionInputMessageFormatter.buildUserMessage(...)` renders input as text-only JSON at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/MissionInputMessageFormatter.java:36`.
- Single-shot mission calls use `.user(userMessage)` at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:152`.
- Step-loop calls use `.user(stepUserMessage)` at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:540`.
- Planning calls use `.user(planningUserMessage)` at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:440`.
- `YamlSkillManifest` currently types both `input_schema` and `output_schema` as `OutputSchemaManifest` at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:38`.
- YAML schema validation accepts only JSON-schema primitive types at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:41`.
- `SkillInputSchemaNode` has a `runtimeRefCapable` marker but no attachment metadata at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputSchemaNode.java:17`.
- Unmapped YAML skills bypass ref materialization because `CapabilityExecutionRouter` sends normalized input directly to `ExecutionCoordinator`, while ref resolution happens only for mapped/non-LLM invocations at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/CapabilityExecutionRouter.java:85`.
- The VFS resolver already strictly resolves `ref://...` values into session-scoped `Resource` values at `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java:9` and `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:20`.
- The sample Java feedstock parser currently constructs an OpenAI `input_image` item manually at `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java:124`.
- Spring AI is already pinned to `1.1.6` at `pom.xml:50`.

## What We're NOT Doing

- Do not remove or replace the existing Java-backed `feedstockTicketParser` sample.
- Do not support arbitrary `file:` or `classpath:` string attachment sources in the core first pass.
- Do not add `byte[]` or `InputStream` as public YAML attachment input values in the first pass.
- Do not render PDFs to images or implement PDF-specific model forwarding in this slice.
- Do not pass newly generated attachments between step-loop tools/skills.
- Do not send raw bytes, base64 data URLs, or copied attachment files into traces.
- Do not make planning inspect attachment media by default.
- Do not add a global MIME allowlist; MIME policy stays per declared skill field.

## Implementation Approach

Implement attachment support as an explicit input-contract feature. Split YAML input schema modeling away from output schema modeling, carry attachment metadata through `SkillInputSchemaNode`, materialize declared attachment fields at the model boundary, and update execution engines to render descriptor text plus Spring AI media.

The model-call change should be isolated behind a small helper so most execution code can continue to say "send this rendered mission input" without duplicating Spring AI multimodal request construction.

## Phase 1: Spring AI Multimodal Spike and Schema Split

### Overview

Verify the concrete Spring AI `UserMessage`/`Media` request shape in this repository, then split input and output manifest schema types so `type: attachment` can be accepted only for `input_schema`. The implementation will use Spring AI 1.1.6's `ChatClientRequestSpec.user(Consumer<PromptUserSpec>)` path unless the characterization test shows that the repository's compiled API requires constructing a `UserMessage` and passing it through `messages(...)`; in either case, the chosen request shape is locked by a permanent test before runtime code is wired.

### Changes Required

#### 1. Spring AI media construction spike

**Files**:
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/attachment/SpringAiMissionUserMessageSenderTest.java`
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/SimpleChatClient.java`

**Changes**:
- Add a failing/characterization test that sends text plus one image media item backed by a Spring `Resource`.
- Update a test chat client to capture the message built through `ChatClientRequestSpec.user(Consumer<PromptUserSpec>)`; if that API does not expose media in the compiled dependency, capture the equivalent `UserMessage` passed through `messages(...)`.
- Keep the test as a permanent guard for the selected request shape.

```java
// Target shape, adjusted to the exact Spring AI 1.1.6 API during implementation.
chatClient.prompt()
        .system(systemPrompt)
        .user(user -> user.text(rendered.userText())
                .media(mediaContentType, attachment.resource()))
        .call();
```

#### 2. Split YAML input schema manifest from output schema manifest

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`

**Changes**:
- Add `InputSchemaManifest` with existing shared schema fields plus input-only attachment fields:
  - `media_type`
  - `allowed_content_types`
- Keep `OutputSchemaManifest` strict and free of attachment-only fields.
- Change `inputSchema` to `InputSchemaManifest`.
- Preserve existing output schema behavior.

```java
@JsonProperty("input_schema")
private InputSchemaManifest inputSchema;

@JsonIgnoreProperties(ignoreUnknown = false)
public static class InputSchemaManifest {
    private String type;
    private Map<String, InputSchemaManifest> properties = Map.of();
    @JsonProperty("media_type")
    private String mediaType;
    @JsonProperty("allowed_content_types")
    private List<String> allowedContentTypes = List.of();
}
```

#### 3. Validate attachment schema only in input schema

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`

**Changes**:
- Add input-specific supported schema types including `attachment`.
- Keep output schema supported types unchanged.
- Reject root `type: attachment`; root remains `object`.
- Validate that attachment nodes do not declare `properties`, `items`, `required`, `additionalProperties`, or `enum`.
- Require `media_type` for attachment nodes; initially support `image`, `pdf`, `audio`, `video`, and `file` as enum values in the manifest contract, with runtime media sending focused on images.
- Require non-empty `allowed_content_types` for first pass so validation policy is explicit.
- Reject `media_type` and `allowed_content_types` on non-attachment nodes.

### Success Criteria

#### Automated Verification

- [x] Spring AI media characterization test passes: `mvn -pl bifrost-spring-boot-starter -Dtest=SpringAiMissionUserMessageSenderTest test`
- [x] YAML catalog accepts valid attachment input schema.
- [x] YAML catalog rejects `type: attachment` in `output_schema`.
- [x] YAML catalog rejects malformed attachment declarations.
- [x] Starter tests pass: `mvn -pl bifrost-spring-boot-starter test`

#### Manual Verification

- [ ] Developer confirms the selected Spring AI API path is clear and does not require a milestone dependency.

**Implementation Note**: After this phase and automated verification pass, pause for manual confirmation that the chosen Spring AI media request shape is acceptable before wiring runtime execution.

---

## Phase 2: Attachment Contract and Materialization

### Overview

Add internal attachment value objects, carry attachment metadata through input contracts, validate runtime values, and materialize declared attachment fields into trace-safe input plus model attachments.

### Changes Required

#### 1. Add attachment runtime model

**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/attachment/AttachmentMediaType.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/attachment/BifrostAttachment.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/attachment/RenderedMissionInput.java`

**Changes**:
- Add an enum for broad media kind.
- Add an internal record carrying field path, name, content type, source, size, digest, metadata, and Spring `Resource`.
- Add rendered input containing prompt text, attachments, and trace-safe input.

```java
public record RenderedMissionInput(
        String userText,
        List<BifrostAttachment> attachments,
        Map<String, Object> traceSafeInput) {
}
```

#### 2. Extend input contract metadata

**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputSchemaNode.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCapabilityRegistrar.java`

**Changes**:
- Add `attachment`, `attachmentMediaType`, and `allowedContentTypes` fields to `SkillInputSchemaNode`.
- Parse these fields from `InputSchemaManifest`.
- Emit stable JSON schema extensions for tool descriptions, for example:
  - `x-bifrost-attachment: true`
  - `x-bifrost-media-type: image`
  - `x-bifrost-allowed-content-types: [...]`
- Include attachment metadata in structural compatibility checks for mapped YAML skills.

#### 3. Validate runtime attachment values

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputValidator.java`

**Changes**:
- Add a `case "attachment"` branch.
- Accept strict `ref://...` strings and Spring `Resource` values.
- Reject `byte[]`, `InputStream`, data URLs, arbitrary strings, and null values unless the field is optional and absent.
- Preserve the original value for materialization rather than resolving refs during validation.

```java
case "attachment" -> validateAttachment(value, schema, path, issues);
```

#### 4. Add materializer

**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/attachment/MissionInputMaterializer.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/attachment/DefaultMissionInputMaterializer.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostSessionProperties.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`

**Changes**:
- Walk the declared input schema and normalized mission input.
- Resolve `ref://...` values through `RefResolver`.
- Accept already-materialized `Resource` values without using `file:` or `classpath:` strings.
- Determine content type from declared allowlist plus resource filename/metadata.
- Enforce declared `allowed_content_types`.
- Enforce a configurable `bifrost.session.attachments.max-size` with a 20 MiB default. The bundled feedstock sample is about 427 KiB, so this leaves ample room for normal images while still preventing accidental large-file forwarding.
- Compute SHA-256 digest when resource content is readable within the size limit.
- Replace attachment input values with descriptors in `traceSafeInput`.
- Build `userText` by calling `MissionInputMessageFormatter.buildUserMessage(objective, traceSafeInput)`.

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

### Success Criteria

#### Automated Verification

- [x] Contract resolver preserves attachment metadata from YAML input schema.
- [x] Input validator accepts strict `ref://...` and `Resource` values for attachment fields.
- [x] Input validator rejects plain filenames, data URLs, `byte[]`, `InputStream`, and non-attachment values.
- [x] Materializer resolves refs against the current session VFS.
- [x] Materializer rejects missing refs with a clear skill/input field error.
- [x] Materializer rejects `text/plain` for an `image/jpeg` attachment field.
- [x] Materializer descriptors contain filename, media type, content type, size when available, source, and digest when available.
- [x] Materializer never includes raw bytes or base64 in trace-safe input.
- [x] Starter tests pass: `mvn -pl bifrost-spring-boot-starter test`

#### Manual Verification

- [ ] Review error messages for invalid refs and MIME mismatches; they should name the field path and expected policy.

**Implementation Note**: After this phase and automated verification pass, pause for manual review of the attachment descriptors and size-limit error message before wiring model execution.

---

## Phase 3: Multimodal Execution Runtime

### Overview

Wire the materializer into single-shot and step-loop execution. Keep planning descriptor-only by default.

### Changes Required

#### 1. Add Spring AI request helper

**Files**:
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/attachment/MissionUserMessageSender.java`
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/attachment/SpringAiMissionUserMessageSender.java`

**Changes**:
- Centralize the decision between `.user(String)` and multimodal user-message construction.
- For zero attachments, preserve current `.user(rendered.userText())` behavior.
- For attachments, send text plus Spring AI media in a `UserMessage`/`Media` shape verified in Phase 1.
- Wrap provider/API failures involving attachments with a clear runtime exception that includes skill name, provider, provider model, attachment count, media types, and a hint to use a vision-capable model.

#### 2. Update single-shot engine

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`

**Changes**:
- Inject `MissionInputMaterializer` and `MissionUserMessageSender`.
- Materialize input before planning and execution.
- Use descriptor-safe input for planning.
- Use rendered media input for the mission model call.
- Update trace input and sent payload to include:
  - `system`
  - `user`
  - `attachments`
  - `attachmentCount`
- Continue usage extraction with prompt text, system prompt, and response content.

#### 3. Update step-loop engine

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java`

**Changes**:
- Inject `MissionInputMaterializer` and `MissionUserMessageSender`.
- Materialize once at the start of mission execution.
- Pass trace-safe mission input into planning and prompt builders.
- Pass actual attachments to each model step and final response call.
- Keep tool call arguments unchanged; generated attachment passing between tools remains out of scope.
- Update model-call trace payloads with descriptor metadata only.

#### 4. Keep planning descriptor-only

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`

**Changes**:
- Either continue receiving descriptor-safe `missionInput`, or explicitly call a descriptor-only renderer if the materializer is made available at planning boundary.
- Do not send attachment media to planning by default.
- Add trace metadata indicating planning used attachment descriptors only when descriptors are present.

#### 5. Wire auto-configuration

**File**: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfiguration.java`

**Changes**:
- Add beans for `MissionInputMaterializer` and `MissionUserMessageSender`.
- Add them to constructors for both mission execution engines.
- Preserve existing public constructors where useful for tests by delegating with default helper instances.

### Success Criteria

#### Automated Verification

- [x] Single-shot engine sends a captured `UserMessage` with image media when attachments are present.
- [x] Single-shot engine still sends text-only `.user(String)` when there are no attachments.
- [x] Step-loop engine sends image media in step model calls when attachments are present.
- [x] Planning receives descriptor text only and no media.
- [x] Trace sent payload includes attachment metadata, not bytes or base64.
- [x] Provider failure wrapping includes skill name, provider model, and attachment media details.
- [x] Starter tests pass: `mvn -pl bifrost-spring-boot-starter test`

#### Manual Verification

- [ ] Inspect a generated execution trace for an attachment run and confirm no raw image bytes or base64 are present.

**Implementation Note**: After this phase and automated verification pass, pause for manual trace inspection before adding the sample endpoint.

---

## Phase 4: Feedstock Pure YAML Sample

### Overview

Add a second feedstock skill that uses first-class image attachments directly through YAML, and expose a sample endpoint that passes the bundled classpath image as a Spring `Resource`. Ref-based invocation is still verified in starter tests with a session VFS resource.

### Changes Required

#### 1. Add pure YAML skill

**File**: `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml`

**Changes**:
- Add `feedstockTicketParserBySkill` with `planning_mode: false`.
- Use a vision-capable sample model key such as `openai-vision`.
- Declare `image` as `type: attachment`, `media_type: image`, `allowed_content_types: [image/jpeg]`.
- Use non-null output schema primitives unless nullable output schema support is added separately.
- Keep the prompt text in the YAML description and schema descriptions.

#### 2. Add sample Resource invocation

**Files**:
- `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java`

**Changes**:
- Inject `ResourceLoader` into the sample controller.
- Load `classpath:/forms/feedstock-p1.jpg` as a `Resource`.
- Pass that resource as `Map.of("image", imageResource)` to avoid adding a public session-staging API solely for the sample.
- Add an endpoint separate from the existing Java-backed endpoint, for example `/feedstock/parse-sample-by-skill`.

```java
Resource image = resourceLoader.getResource("classpath:/forms/feedstock-p1.jpg");
String result = skillTemplate.invoke(
        "feedstockTicketParserBySkill",
        Map.of("image", image),
        holder::set);
```

#### 3. Add sample configuration

**Files**:
- `bifrost-sample/src/main/resources/application.yml`
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleControllerTest.java`

**Changes**:
- Add an `openai-vision` model entry when sample configuration can safely reference a vision-capable provider model.
- Add controller tests proving the new sample endpoint invokes the new skill with a `Resource` image argument.
- Keep the existing `feedstockTicketParser` Java-backed endpoint unchanged.

### Success Criteria

#### Automated Verification

- [x] Sample skill registers successfully in `YamlSkillCatalog`.
- [x] Existing Java-backed feedstock skill still registers.
- [x] New sample controller test verifies invocation of `feedstockTicketParserBySkill` with a `Resource` image input.
- [x] Full reactor tests pass: `mvn test`

#### Manual Verification

- [ ] With a configured vision-capable model and API key, `/feedstock/parse-sample-by-skill` returns schema-shaped JSON.
- [ ] `/feedstock/parse-sample` still uses the existing Java-backed parser.

**Implementation Note**: After this phase and automated verification pass, run the pure YAML sample manually only when provider credentials are available.

---

## Testing Strategy

### Unit Tests

- Manifest parsing accepts input-only attachment schema fields.
- Manifest parsing rejects attachment fields on output schemas.
- `SkillInputContractResolver` preserves attachment metadata and emits stable tool-schema extensions.
- `SkillInputValidator` accepts and rejects first-pass attachment value shapes correctly.
- `DefaultMissionInputMaterializer` resolves refs, validates content type and size, creates descriptors, and redacts bytes.
- `SpringAiMissionUserMessageSender` selects text-only or multimodal request paths correctly.

### Integration Tests

- Single-shot execution captures an image media message with trace-safe descriptors.
- Step-loop execution captures image media on model-step calls.
- Planning captures descriptor-only input.
- Execution trace contract tests verify raw bytes/base64 are absent.
- Sample catalog/controller tests verify both old and new feedstock skills.

### Manual Testing Steps

1. Configure a session VFS root and stage `feedstock-p1.jpg` under `forms/feedstock-p1.jpg`.
2. Invoke `feedstockTicketParserBySkill` with `image: ref://forms/feedstock-p1.jpg`.
3. Confirm the model request includes image media through the fake/captured request in tests.
4. With provider credentials, call the sample endpoint and confirm schema-shaped extraction output.
5. Inspect trace output for attachment descriptors only.

Use `ai/commands/3_testing_plan.md` before implementation to create a dedicated test plan artifact with failing-test-first sequencing and exact exit criteria.

## Performance Considerations

- Resolve and hash attachment resources once per mission execution, then reuse the materialized attachments across step-loop model calls.
- Enforce a configurable max attachment size before provider calls to avoid unbounded memory use.
- Avoid copying attachment files into traces.
- Keep content-type detection filename/metadata based in the first pass; add bounded sniffing only if filename/resource metadata proves fragile.

## Migration Notes

- Existing YAML skills without attachment schema should behave exactly as they do today.
- Existing mapped Java skills keep their current ref-resolution behavior.
- Existing output schemas remain JSON-schema-like and do not learn `type: attachment`.
- Existing execution traces gain optional attachment metadata for attachment-aware runs only.
- The existing Java-backed feedstock sample remains available and unchanged.

## References

- Original ticket/requirements: `ai/thoughts/tickets/eng-first-class-attachments-for-yaml-skills.md`
- Related research: `ai/thoughts/research/2026-05-17-first-class-attachments-yaml-skills.md`
- Planning command: `ai/commands/2_create_plan.md`
- Text-only mission formatter: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/core/MissionInputMessageFormatter.java:36`
- Single-shot model call: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java:152`
- Step-loop model call: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java:540`
- Planning model call: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java:440`
- YAML schema model: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java:307`
- YAML schema validation: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java:360`
- Input contract node: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputSchemaNode.java:17`
- Runtime input validation: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputValidator.java:245`
- Ref resolver: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/DefaultRefResolver.java:9`
- Session VFS: `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/vfs/SessionLocalVirtualFileSystem.java:20`
- Existing feedstock Java parser: `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/FeedstockFormExtractionService.java:124`
