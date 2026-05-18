# First-Class Attachments for YAML Skills Testing Plan

## Change Summary

- YAML skills will be able to declare `type: attachment` fields in `input_schema`.
- First-pass runtime values for attachment fields will be strict `ref://...` strings and Spring `Resource` instances.
- Attachment input will be materialized into two forms:
  - trace/prompt-safe descriptors for canonical mission input
  - actual Spring AI media attachments for execution model calls
- Single-shot and step-loop execution will send image attachments as model media.
- Planning will remain descriptor-only by default.
- Execution traces will include attachment metadata but never raw bytes or base64 payloads.
- The sample app will add a pure YAML feedstock parser while preserving the existing Java-backed parser.

## Impacted Areas

- YAML manifest parsing and validation:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
- Input contracts and validation:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputSchemaNode.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolver.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/input/SkillInputValidator.java`
- Attachment materialization and Spring AI request construction:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/attachment/*`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/SimpleChatClient.java`
- Mission execution:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngine.java`
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/planning/DefaultPlanningService.java`
- Trace contracts:
  - `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/state/DefaultExecutionStateService.java`
  - `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceContractTest.java`
- Sample app:
  - `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml`
  - `bifrost-sample/src/main/java/com/lokiscale/bifrost/sample/SampleController.java`
  - `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleControllerTest.java`

## Risk Assessment

- Spring AI media API mismatch: the compiled `1.1.6` API may require a different request shape than assumed.
- Schema-model split regression: output schemas could accidentally accept input-only attachment fields, or existing input/output schema validation could change for ordinary skills.
- Validator looseness: accepting filenames, data URLs, `byte[]`, or `InputStream` would expand the first-pass public contract beyond the ticket.
- Ref resolution errors: missing or escaping refs must remain session-scoped and produce useful field-specific failures.
- Trace leakage: raw image bytes, base64 data URLs, or `Resource` internals could leak through prepared/sent payloads or mission frame parameters.
- Planning leakage: planner calls could accidentally receive actual media attachments instead of descriptors only.
- Execution asymmetry: single-shot execution could work while step-loop or final-response calls remain text-only.
- Backwards compatibility: existing YAML skills and Java-backed ref-capable skills must keep their behavior.
- Sample drift: adding a pure YAML feedstock sample must not break the existing Java-backed endpoint.

## Existing Test Coverage

- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java` covers YAML manifest loading, input schema validation, output schema validation, unknown fields, compatibility checks, and catalog registration.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/input/SkillInputValidatorTest.java` covers scalar/object/array validation, date normalization, generic contracts, and current Java-reflected runtime ref values.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java` covers single-shot model execution, trace payloads, timeouts, and failure frame status.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngineTest.java` covers step-loop model calls, planning interaction, final response generation, and trace events.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java` covers planner model-call payloads and plan quality retry behavior.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/trace/ExecutionTraceContractTest.java` covers semantic consistency of model trace envelopes across planning and mission calls.
- `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/core/ExecutionCoordinatorIntegrationTest.java` covers end-to-end starter wiring and strict ref resolution for Java-backed tools.
- `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleControllerTest.java` covers current sample controller delegation.

## Gaps

- No tests assert that YAML `input_schema` and `output_schema` are modeled independently.
- No tests cover `type: attachment`, `media_type`, or `allowed_content_types`.
- No tests capture Spring AI multimodal user-message media.
- No tests prove planning stays descriptor-only when execution has attachments.
- No tests prove trace records are free of raw bytes/base64 for attachment runs.
- No tests cover image content-type policy, size limits, or attachment descriptor shape.
- No tests cover pure YAML feedstock sample registration.

## Bug Reproduction / Failing Test First

### Primary Failing Test

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
- Name: `sendsDeclaredImageAttachmentAsMediaInsteadOfTextOnlyUserMessage`
- Arrange:
  - Create a `YamlSkillDefinition` with an explicit `input_schema` containing required `image` attachment field.
  - Use a small `ByteArrayResource` named `ticket.jpg` with content that is not valid text and MIME policy `image/jpeg`.
  - Use a capture-capable fake `ChatClient` that records whether `.user(String)` or multimodal user-message media was used.
- Act:
  - Execute the single-shot engine with `Map.of("image", resource)`.
- Assert:
  - Exactly one media attachment is present on the captured user message.
  - The media content type is `image/jpeg`.
  - The user text contains a descriptor with `attachment`, `name`, `contentType`, and `mediaType`.
  - The user text does not contain raw bytes, byte-array object text, or base64.
  - The text-only `.user(String)` path was not used for the attachment-bearing execution call.
- Expected failure pre-fix:
  - The current engine calls `.user(userMessage)` and stringifies the resource inside canonical mission input; no media is captured.

### Secondary Failing Test

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- Name: `rejectsAttachmentFieldsOnOutputSchema`
- Arrange:
  - Load a YAML skill whose `output_schema.properties.image` declares `type: attachment`, `media_type: image`, and `allowed_content_types`.
- Act:
  - Initialize `YamlSkillCatalog`.
- Assert:
  - Catalog startup fails with a message naming `output_schema.properties.image.type`.
- Expected failure pre-fix:
  - Current code rejects `attachment` as an unsupported schema type, but this test should be kept after implementation to prove output schemas remain strict while input schemas gain attachment support.

## Tests to Add/Update

### 1) `capturesSpringAiMediaRequestShape`

- Type: unit/characterization
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/attachment/SpringAiMissionUserMessageSenderTest.java`
- What it proves:
  - The repository's compiled Spring AI `1.1.6` API can carry text plus `Resource` image media through the selected request path.
  - The request helper uses the multimodal path when attachments are present.
- Fixtures/data:
  - `ByteArrayResource` with filename `ticket.jpg`.
  - `RenderedMissionInput` with one `BifrostAttachment`.
- Mocks:
  - Capture-capable fake `ChatClient` or upgraded `SimpleChatClient`.
  - The fake should record string-user calls, consumer-user calls, `messages(...)` calls, and captured media.

### 2) `fallsBackToTextOnlyUserMessageWhenNoAttachmentsArePresent`

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/attachment/SpringAiMissionUserMessageSenderTest.java`
- What it proves:
  - Existing text-only behavior is preserved for ordinary YAML skills.
- Fixtures/data:
  - `RenderedMissionInput` with empty attachments.
- Mocks:
  - Capture-capable fake `ChatClient`.

### 3) `acceptsAttachmentOnlyInInputSchema`

- Type: integration/unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - `input_schema` accepts `type: attachment`, `media_type`, and `allowed_content_types`.
  - `output_schema` still rejects attachment type and attachment-only fields.
- Fixtures/data:
  - Test YAML resources under `bifrost-spring-boot-starter/src/test/resources/skills/valid/attachment-input-skill.yaml`.
  - Invalid YAML resources under `bifrost-spring-boot-starter/src/test/resources/skills/invalid/attachment-output-skill.yaml`.
- Mocks:
  - Existing `ApplicationContextRunner`/catalog test pattern.

### 4) `requiresAllowedContentTypesForAttachmentInput`

- Type: unit/integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/YamlSkillCatalogTests.java`
- What it proves:
  - Attachment fields require explicit MIME policy.
  - Non-attachment fields reject `media_type` and `allowed_content_types`.
- Fixtures/data:
  - Invalid YAML with missing `allowed_content_types`.
  - Invalid YAML with `type: string` plus `media_type`.
- Mocks:
  - Existing catalog test pattern.

### 5) `preservesAttachmentMetadataInInputContract`

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/input/SkillInputContractResolverTest.java`
- What it proves:
  - `SkillInputContractResolver.fromManifest(...)` maps attachment metadata into `SkillInputSchemaNode`.
  - `toJsonSchema(...)` emits stable Bifrost extensions for tool/capability descriptions.
  - Structural compatibility compares attachment metadata.
- Fixtures/data:
  - Programmatic `InputSchemaManifest` with image attachment field.
- Mocks:
  - None.

### 6) `validatesFirstPassAttachmentInputShapes`

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/input/SkillInputValidatorTest.java`
- What it proves:
  - Strict `ref://forms/ticket.jpg` strings and Spring `Resource` values are accepted.
  - Plain filenames, `file:` strings, `classpath:` strings, data URLs, `byte[]`, `InputStream`, and arbitrary objects are rejected.
  - The normalized input preserves accepted values for materialization.
- Fixtures/data:
  - `ByteArrayResource`.
  - `ByteArrayInputStream`.
  - Representative strings.
- Mocks:
  - None.

### 7) `materializesRefAttachmentToDescriptorAndResource`

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/attachment/DefaultMissionInputMaterializerTest.java`
- What it proves:
  - A declared attachment field containing `ref://forms/ticket.jpg` resolves through `RefResolver`.
  - The result contains one `BifrostAttachment`.
  - `traceSafeInput` replaces the input value with descriptor metadata.
  - The generated prompt text includes the descriptor, not bytes.
- Fixtures/data:
  - Temp session VFS root with `forms/ticket.jpg`.
  - `BifrostSession` with known session id.
- Mocks:
  - Real `SessionLocalVirtualFileSystem` and `DefaultRefResolver`.

### 8) `rejectsAttachmentWhenContentTypePolicyDoesNotMatch`

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/attachment/DefaultMissionInputMaterializerTest.java`
- What it proves:
  - A `.txt` resource is rejected for an `image/jpeg` field.
  - Error message includes the attachment field path, actual content type or filename, and allowed content types.
- Fixtures/data:
  - Temp VFS file `forms/not-image.txt`.
- Mocks:
  - Real VFS/ref resolver.

### 9) `rejectsAttachmentAboveConfiguredSizeLimit`

- Type: unit
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/attachment/DefaultMissionInputMaterializerTest.java`
- What it proves:
  - The configured `bifrost.session.attachments.max-size` guard is enforced before provider calls.
  - Error message includes field path and configured limit.
- Fixtures/data:
  - `ByteArrayResource` or temp file larger than a tiny test limit.
- Mocks:
  - None, or real VFS/ref resolver for ref variant.

### 10) `singleShotTraceRedactsAttachmentBytes`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
- What it proves:
  - `MODEL_REQUEST_PREPARED` and `MODEL_REQUEST_SENT` records contain attachment descriptors.
  - Trace data contains no raw bytes, no base64 payload, and no original `Resource` object serialization.
- Fixtures/data:
  - Attachment-bearing YAML definition.
  - `ByteArrayResource` whose bytes include a sentinel value such as `SECRET_IMAGE_BYTES`.
- Mocks:
  - Capture-capable fake chat client returning `"mission complete"`.

### 11) `planningReceivesDescriptorsButNoMedia`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/planning/PlanningServiceTest.java`
- What it proves:
  - Planner user text contains attachment descriptor metadata.
  - Planner call captures zero media attachments.
  - Planner traces are descriptor-only.
- Fixtures/data:
  - Attachment descriptor input produced by materializer, or engine-level planning-enabled execution with attachment input.
- Mocks:
  - Capture-capable planning chat client.

### 12) `stepLoopSendsAttachmentMediaOnExecutionSteps`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/runtime/step/StepLoopMissionExecutionEngineTest.java`
- What it proves:
  - Step-loop model calls receive image media during execution/final-response calls.
  - Prompt/user text uses trace-safe descriptors.
  - Planning initialization receives descriptor-safe input only.
- Fixtures/data:
  - Step-loop skill definition with attachment input schema.
  - `SequenceChatClient` upgraded to capture media counts by call.
- Mocks:
  - Existing `InitializingPlanningService`.
  - Existing sequence/fake chat client pattern.

### 13) `wrapsProviderFailureWithAttachmentContext`

- Type: unit/integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/skill/MissionExecutionEngineTest.java`
- What it proves:
  - If the provider call fails after an attachment-bearing request is attempted, the thrown exception includes skill name, provider, provider model, attachment media type/content type, and a vision-capable model hint.
- Fixtures/data:
  - Attachment-bearing definition and resource.
- Mocks:
  - Failing chat client that throws from `.call()`.

### 14) `autoConfigurationWiresAttachmentServices`

- Type: integration
- Location: `bifrost-spring-boot-starter/src/test/java/com/lokiscale/bifrost/autoconfigure/BifrostAutoConfigurationTests.java`
- What it proves:
  - `MissionInputMaterializer` and `MissionUserMessageSender` are auto-configured.
  - Both mission engines receive attachment-capable collaborators under starter wiring.
  - `bifrost.session.attachments.max-size` binds to configuration.
- Fixtures/data:
  - `ApplicationContextRunner`.
- Mocks:
  - Existing chat model mocks.

### 15) `pureYamlFeedstockSkillRegisters`

- Type: integration
- Location: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleControllerTest.java` or a new `SampleSkillCatalogTest`
- What it proves:
  - `feedstockTicketParserBySkill` YAML manifest loads.
  - Existing `feedstockTicketParser` Java-backed skill still loads.
  - The pure YAML skill has an attachment input contract.
- Fixtures/data:
  - `bifrost-sample/src/main/resources/skills/feedstock_ticket_parser_by_skill.yml`.
- Mocks:
  - Application context with sample model config.

### 16) `sampleControllerDelegatesPureYamlFeedstockResourceToSkillTemplate`

- Type: unit
- Location: `bifrost-sample/src/test/java/com/lokiscale/bifrost/sample/SampleControllerTest.java`
- What it proves:
  - New sample endpoint invokes `feedstockTicketParserBySkill`.
  - Input map contains an `image` value that is a Spring `Resource`.
  - Existing `/feedstock/parse-sample` behavior remains unchanged.
- Fixtures/data:
  - Mock `SkillTemplate`.
  - Mock or real `ResourceLoader`.
- Mocks:
  - Mockito argument capture for the input map.

## How to Run

- Focused Spring AI request-shape spike:
  - `mvn -pl bifrost-spring-boot-starter -Dtest=SpringAiMissionUserMessageSenderTest test`
- Manifest and input contract tests:
  - `mvn -pl bifrost-spring-boot-starter -Dtest=YamlSkillCatalogTests,SkillInputContractResolverTest,SkillInputValidatorTest test`
- Materializer tests:
  - `mvn -pl bifrost-spring-boot-starter -Dtest=DefaultMissionInputMaterializerTest test`
- Execution and trace tests:
  - `mvn -pl bifrost-spring-boot-starter -Dtest=MissionExecutionEngineTest,StepLoopMissionExecutionEngineTest,PlanningServiceTest,ExecutionTraceContractTest test`
- Starter auto-configuration tests:
  - `mvn -pl bifrost-spring-boot-starter -Dtest=BifrostAutoConfigurationTests test`
- Sample tests:
  - `mvn -pl bifrost-sample test`
- Full verification:
  - `mvn test`

## Exit Criteria

- [ ] Primary failing test exists and fails pre-fix because attachment input is sent text-only.
- [ ] Spring AI request-shape characterization test passes and documents the selected media request path.
- [ ] Input schema accepts attachments while output schema rejects attachments.
- [ ] Validator accepts only strict `ref://...` and Spring `Resource` attachment values.
- [ ] Materializer resolves refs, enforces MIME policy, enforces size limits, computes descriptors, and redacts bytes.
- [ ] Single-shot execution sends image media and trace-safe descriptor text.
- [ ] Step-loop execution sends image media on execution/final-response model calls.
- [ ] Planning receives descriptors and zero media by default.
- [ ] Trace contract tests prove no raw bytes, base64, or `Resource` serialization leaks into model trace payloads.
- [ ] Provider failure wrapping includes skill/model/attachment context.
- [ ] Existing text-only YAML skills and Java-backed ref-capable skills remain covered and passing.
- [ ] Pure YAML feedstock sample registers and the new controller endpoint delegates with a `Resource` image.
- [ ] Full reactor passes with `mvn test`.

## Manual Verification

- With provider credentials configured for a vision-capable model, call the new pure YAML feedstock endpoint and confirm it returns schema-shaped JSON.
- Inspect one generated trace from an attachment run and confirm it contains descriptor metadata only.
- Confirm the existing Java-backed `/feedstock/parse-sample` endpoint still works and still invokes `feedstockTicketParser`.
