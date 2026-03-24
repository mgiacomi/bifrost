# ENG-024 - Output Schema Support For YAML Skills

## Summary

Add first-class `output_schema` support for YAML-defined skills so developers can declare an inline structured-output contract for LLM-backed skills. When present, Bifrost should instruct the model to return JSON only, validate the returned payload against a supported JSON-Schema-inspired subset, retry bounded times when validation fails, and raise a custom exception containing the raw model output plus validation issues when the output never satisfies the schema.

This feature is scoped to YAML skills only for the MVP. It is intentionally separate from the existing `linter` feature, but should compose with regex linting when both are configured.

## Problem Statement

Today Bifrost supports:

- Typed input schemas for `@SkillMethod` tool inputs.
- Regex-based YAML skill linting with bounded retries.
- Strict YAML manifest validation at startup.

Today Bifrost does not support:

- Declaring structured output contracts for YAML skills.
- Teaching the model the exact output object shape in a framework-native way.
- Validating model output semantically as JSON/object fields instead of only raw text matching.

This creates a gap for extraction-oriented skills such as invoice parsing, form extraction, receipt normalization, and lightweight data transformation tasks. Developers can describe the desired output in prose, but Bifrost cannot enforce or meaningfully guide the model toward the expected structure.

## Goals

- Add an inline `output_schema` field to YAML skill manifests.
- Support a documented JSON-Schema-inspired subset suitable for common extraction tasks.
- When `output_schema` is present, instruct the model to return JSON only.
- Validate returned model output in three stages:
  - JSON parsing
  - schema validation
  - regex linting, if configured
- Support automatic retries for schema validation failures with a bounded retry count.
- Preserve the raw model output when validation fails, even if the output is not valid JSON.
- Throw a custom exception on terminal schema failure with raw output and validation issues.
- Log startup warnings for unusually complex schemas without failing startup.
- Preserve the existing return type of successful YAML skill execution as `String`.

## Non-Goals

- No support for `@SkillMethod` output schemas in the MVP.
- No output coercion, repair, or normalization in the MVP.
- No schema references, external files, or Java class references in the MVP.
- No full JSON Schema dialect support.
- No root-array schema support in the MVP.
- No canonical reformatting of successful JSON output in the MVP.
- No diagnostics API for schema failures in the MVP beyond logs, session/journal recording, and exception fields.

## Proposed YAML Contract

```yaml
name: invoice_parser
description: Extract invoice fields from OCR text.
model: default-model

output_schema:
  type: object
  properties:
    vendorName:
      type: string
      description: Legal vendor name
    totalAmount:
      type: number
      description: Total invoice amount including tax
    invoiceDate:
      type: string
      format: date
      description: ISO-8601 invoice date
  required:
    - vendorName
    - totalAmount
    - invoiceDate
  additionalProperties: false

output_schema_max_retries: 2

linter:
  type: regex
  max_retries: 1
  regex:
    pattern: '^\\{[\\s\\S]*\\}$'
    message: 'Return raw JSON only.'
```

## Supported `output_schema` Subset For MVP

The contract should be explicitly documented as a JSON-Schema-inspired subset, not full JSON Schema.

Supported keywords:

- `type`
- `properties`
- `required`
- `additionalProperties`
- `items`
- `enum` for string fields
- `description`
- `format`
- nested objects
- arrays of scalars
- arrays of simple objects

Behavior notes:

- Root schema must be `type: object`.
- `enum` is supported only for `type: string` in the MVP.
- `format` is prompt guidance only in the MVP. It should not cause validation failure.
- `description` is prompt guidance only.
- Unknown schema keywords must fail startup.
- Deep or large schemas may emit warnings but still load.

Unsupported examples for MVP:

- `$ref`
- `oneOf`
- `anyOf`
- `allOf`
- `not`
- `if` / `then` / `else`
- `patternProperties`
- `dependencies`
- root arrays

## Manifest Validation Rules

Add startup validation for `output_schema` and `output_schema_max_retries`.

Rules:

- `output_schema` is optional.
- `output_schema_max_retries` is only valid when `output_schema` is present.
- If `output_schema` is present and `output_schema_max_retries` is omitted, default to `2`.
- `output_schema_max_retries` must be an integer in a bounded safe range.
  - Suggested MVP range: `0..3` for consistency with linter retries.
- `output_schema.type` is required and must be `object` at the root.
- `required` entries must all exist in `properties`.
- Property names that differ only by case must fail startup because runtime matching is case-insensitive.
- Unknown schema keywords must fail startup.
- Inline object form only. Stringified JSON is not allowed in the manifest.

Validation warnings to log, but not fail:

- schema nesting beyond a small recommended depth
- large property counts
- large `required` lists
- arrays of nested objects

## Runtime Execution Semantics

When a skill has `output_schema`:

1. Bifrost augments the system prompt with a structured-output instruction.
2. The instruction tells the model to return JSON only.
3. The model response is validated in this order:
   - parse as JSON
   - validate against `output_schema`
   - if schema passes and regex linter exists, run regex linter against the raw model text
4. If parsing or schema validation fails, Bifrost retries up to `output_schema_max_retries`.
5. Retry hints must include:
   - a short reminder to follow `output_schema`
   - a concise list of validation issues
6. Retry hints must not re-embed the full schema on every retry.
7. If retries are exhausted, Bifrost throws a custom exception containing the raw model output and validation issues.
8. If validation succeeds, Bifrost returns the original raw JSON string as-is.

## Validation Failure Modes

Bifrost should distinguish three failure modes:

1. Response is not valid JSON
2. Response is valid JSON but does not satisfy `output_schema`
3. Response satisfies `output_schema` but fails regex linting

These should be observable separately in logs and any recorded session/journal metadata.

## Field Matching Semantics

To be more forgiving for smaller models, property-name validation should be case-insensitive.

Rules:

- Returned keys should be matched to schema keys case-insensitively.
- Retry hints and issue messages should always use canonical schema field names.
- If two returned keys collapse to the same canonical key ignoring case, validation fails as ambiguous.
- Schema definitions containing keys that differ only by case must fail startup.

Example:

- schema: `vendorName`
- output: `VendorName`
- result: accepted

Ambiguous example:

- schema: `vendorName`
- output: `vendorName` and `VendorName`
- result: validation failure

## `additionalProperties` Default

For the MVP, `additionalProperties` should default to `false` when omitted.

Rationale:

- Bifrost's primary use case here is extraction and normalization.
- Strict field control catches common small-model drift such as `companyName` vs `vendorName`.
- This is intentionally stricter than vanilla JSON Schema defaults and must be documented clearly as Bifrost behavior.

## Retry Hint Design

Retry hints should be concise and action-oriented.

Example:

```text
Output schema validation failed for the previous response.
Return JSON only and satisfy the configured output_schema.
Issues:
- Missing required field `vendorName`
- Unknown field `companyName`
- Field `invoiceDate` should be a string
```

Guidance:

- Include multiple issues, not just the first issue.
- Cap the number of listed issues to avoid prompt bloat.
  - Suggested cap: 3 to 5 issues.
- Do not paste the full schema into each retry hint.

## Custom Exception

Add a custom runtime exception, for example:

- `BifrostOutputSchemaValidationException`

Suggested fields:

- `skillName`
- `rawOutput`
- `validationIssues`
- `attemptCount`
- `maxRetries`
- `failureMode`

Behavior:

- `rawOutput` should contain whatever the LLM returned, even if it was not valid JSON.
- `validationIssues` should contain structured issue details suitable for application handling.
- The exception message should include a concise human-readable summary.

Rationale:

- Developers should be able to access the invalid payload easily.
- Bifrost should not return invalid payloads as if they passed validation.
- Applications may still choose to salvage or inspect invalid output if desired.

## Interplay With Existing Regex Linter

`output_schema` is not a linter type and should not be modeled as one.

Composition rules:

- `output_schema` is a first-class structured-output contract.
- `linter` remains an optional additional verification gate.
- When both exist:
  - parse and schema validation run first
  - regex linting runs second
- Regex linting should inspect the raw model text, not a reformatted version.

Rationale:

- Schema validation answers "did the model return the right structured data?"
- Regex answers "did the raw response text satisfy extra formatting/content constraints?"

## Prompting Requirements

When `output_schema` exists, the prompt should tell the model:

- return JSON only
- do not return prose or markdown fences
- follow the configured field names exactly
- omit unknown fields unless explicitly allowed

The prompt should include enough schema information for the model to understand:

- expected property names
- property types
- required fields
- useful descriptions

The framework should avoid overly verbose prompt injection that repeats the schema multiple times.

## Observability

For MVP:

- log startup warnings for complex schemas
- log schema validation failures and retry attempts
- record terminal failure details in session/journal metadata where practical
- keep the final return type unchanged for success paths

Future work can expose richer diagnostics APIs if needed.

## Suggested Implementation Areas

Primary code areas likely to change:

- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillManifest.java`
  - add `output_schema`
  - add `output_schema_max_retries`
  - add typed schema manifest classes
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/skill/YamlSkillCatalog.java`
  - startup validation for schema subset
  - defaulting and warnings
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/chat/DefaultSkillAdvisorResolver.java`
  - may need evolution if output-schema retries are implemented through advisors
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/linter/LinterCallAdvisor.java`
  - only if shared retry machinery is extracted rather than duplicated
- `bifrost-spring-boot-starter/src/main/java/com/lokiscale/bifrost/runtime/DefaultMissionExecutionEngine.java`
  - inject structured-output prompt guidance if this is not handled by advisors
- new package/classes for schema validation and exception types

## Suggested Acceptance Criteria

- YAML skills may declare inline `output_schema` and optional `output_schema_max_retries`.
- Skills with `output_schema` load successfully when they use only supported schema keywords.
- Unknown schema keywords fail startup with clear field-path errors.
- `output_schema_max_retries` without `output_schema` fails startup.
- `required` entries not present in `properties` fail startup.
- Root schemas other than `type: object` fail startup.
- Property names differing only by case fail startup.
- When `output_schema` exists, execution instructs the model to return JSON only.
- Non-JSON model output triggers schema retry behavior.
- JSON output with missing/unknown/wrong-shape fields triggers schema retry behavior.
- Retry hints list multiple issues plus a short reminder, without repeating the full schema.
- Successful schema validation returns the original raw JSON string.
- When retries are exhausted, Bifrost throws `BifrostOutputSchemaValidationException`.
- The exception exposes the raw LLM output and validation issues.
- If regex linter also exists, regex runs only after schema validation passes.
- Complex but supported schemas emit warnings in logs without failing startup.

## Suggested Tests

Catalog validation tests:

- valid shallow object schema loads
- `output_schema_max_retries` defaults to `2`
- `output_schema_max_retries` without `output_schema` fails
- unknown schema keyword fails
- root array fails
- `required` missing from `properties` fails
- duplicate property names by case fails
- complex schema emits warning

Runtime tests:

- valid JSON passes with no retry
- invalid JSON retries and then passes
- valid JSON with missing required field retries and then passes
- valid JSON with unknown field retries and then passes
- valid JSON with ambiguous key casing fails
- exhausted retries throw custom exception with raw output
- regex linter runs after schema passes
- successful payload is returned exactly as produced

## Risks

- Smaller models may still struggle with nested schemas or arrays of objects.
- Case-insensitive matching is developer-friendly but diverges from standard JSON Schema semantics.
- Defaulting `additionalProperties` to `false` is intentionally strict and may surprise some users if not documented clearly.
- Prompt growth from schema embedding and issue lists may increase token usage.
- Sharing retry behavior between schema validation and regex linting may require careful factoring to avoid duplicated control flow.

## Open Follow-On Ideas

- provider-native structured output when a model/provider supports it
- optional validate-only mode without retries
- optional lenient parsing/repair mode
- external schema refs or `schema_ref`
- Java-type-derived output schemas
- diagnostics API for last invalid payload and issues
