package com.lokiscale.bifrost.runtime.input;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillInputValidatorTest {

    private final SkillInputValidator validator = new SkillInputValidator();

    @Test
    void validatesAndNormalizesInputContractCases() {
        SkillInputContract contract = new SkillInputContract(
                SkillInputContract.SkillInputContractKind.YAML_EXPLICIT,
                new SkillInputSchemaNode(
                        "object",
                        Map.of(
                                "payload", new SkillInputSchemaNode("string", Map.of(), List.of(), null, null, List.of(), null, null, false),
                                "count", new SkillInputSchemaNode("integer", Map.of(), List.of(), null, null, List.of(), null, null, false),
                                "mode", new SkillInputSchemaNode("string", Map.of(), List.of(), null, null, List.of("A", "B"), null, null, false),
                                "options", new SkillInputSchemaNode(
                                        "object",
                                        Map.of("enabled", new SkillInputSchemaNode("boolean", Map.of(), List.of(), null, null, List.of(), null, null, false)),
                                        List.of("enabled"),
                                        Boolean.FALSE,
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        false)),
                        List.of("payload", "options"),
                        Boolean.FALSE,
                        null,
                        List.of(),
                        null,
                        null,
                        false));

        SkillInputValidationResult result = validator.validate(Map.of(
                "payload", "hello",
                "count", "3",
                "mode", "C",
                "options", Map.of(),
                "extra", "nope"), contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.normalizedInput().get("count")).isEqualTo(3);
        assertThat(result.issues()).extracting(SkillInputValidationIssue::code)
                .contains("enum_mismatch", "missing_required", "unknown_field");
    }

    @Test
    void normalizesSupportedDateFormatsAndRejectsUnsupportedDates() {
        SkillInputContract contract = new SkillInputContract(
                SkillInputContract.SkillInputContractKind.YAML_EXPLICIT,
                new SkillInputSchemaNode(
                        "object",
                        Map.of("invoiceDate", new SkillInputSchemaNode("string", Map.of(), List.of(), null, null, List.of(), null, "date", false)),
                        List.of("invoiceDate"),
                        Boolean.FALSE,
                        null,
                        List.of(),
                        null,
                        null,
                        false));

        SkillInputValidationResult accepted = validator.validate(Map.of("invoiceDate", "3/30/2026"), contract);
        SkillInputValidationResult rejected = validator.validate(Map.of("invoiceDate", "2026-03-30T10:15:00"), contract);

        assertThat(accepted.valid()).isTrue();
        assertThat(accepted.normalizedInput().get("invoiceDate")).isEqualTo("2026-03-30");
        assertThat(rejected.valid()).isFalse();
        assertThat(rejected.issues()).extracting(SkillInputValidationIssue::code).containsExactly("invalid_date_format");
    }

    @Test
    void genericContractRemainsPermissive() {
        SkillInputValidationResult result = validator.validate(Map.of("anything", List.of("goes")), SkillInputContract.genericObject());

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void preservesNullValuesInsteadOfThrowing() {
        SkillInputContract contract = new SkillInputContract(
                SkillInputContract.SkillInputContractKind.YAML_EXPLICIT,
                new SkillInputSchemaNode(
                        "object",
                        Map.of("optionalField", new SkillInputSchemaNode("string", Map.of(), List.of(), null, null, List.of(), null, null, false)),
                        List.of(),
                        Boolean.TRUE,
                        null,
                        List.of(),
                        null,
                        null,
                        false));

        java.util.LinkedHashMap<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("optionalField", null);

        SkillInputValidationResult result = validator.validate(input, contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.normalizedInput()).containsEntry("optionalField", null);
        assertThat(result.issues()).extracting(SkillInputValidationIssue::code).containsExactly("type_mismatch");
    }

    @Test
    void allowsRuntimeRefBackedValuesForRefFriendlyStringContracts() {
        SkillInputContract contract = new SkillInputContract(
                SkillInputContract.SkillInputContractKind.JAVA_REFLECTED,
                new SkillInputSchemaNode(
                        "object",
                        Map.of("payload", new SkillInputSchemaNode(
                                "string",
                                Map.of(),
                                List.of(),
                                null,
                                null,
                                List.of(),
                                "Provide a ref:// URI for binary content or an inline string value when appropriate.",
                                null,
                                true)),
                        List.of("payload"),
                        Boolean.FALSE,
                        null,
                        List.of(),
                        null,
                        null,
                        false));

        SkillInputValidationResult result = validator.validate(
                Map.of("payload", new ByteArrayResource(new byte[]{1, 2, 3})),
                contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.normalizedInput().get("payload")).isInstanceOf(ByteArrayResource.class);
    }

    @Test
    void doesNotInferRuntimeRefSupportFromDescriptionTextAlone() {
        SkillInputContract contract = new SkillInputContract(
                SkillInputContract.SkillInputContractKind.YAML_EXPLICIT,
                new SkillInputSchemaNode(
                        "object",
                        Map.of("payload", new SkillInputSchemaNode(
                                "string",
                                Map.of(),
                                List.of(),
                                null,
                                null,
                                List.of(),
                                "This help text mentions ref:// but is not a runtime binding contract.",
                                null,
                                false)),
                        List.of("payload"),
                        Boolean.FALSE,
                        null,
                        List.of(),
                        null,
                        null,
                        false));

        SkillInputValidationResult result = validator.validate(
                Map.of("payload", new ByteArrayResource(new byte[]{1, 2, 3})),
                contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(SkillInputValidationIssue::code).containsExactly("type_mismatch");
    }

    @Test
    void validatesMapLikeAdditionalPropertiesAgainstNestedSchema() {
        SkillInputContract contract = new SkillInputContract(
                SkillInputContract.SkillInputContractKind.JAVA_REFLECTED,
                new SkillInputSchemaNode(
                        "object",
                        Map.of("payload", new SkillInputSchemaNode(
                                "object",
                                Map.of(),
                                List.of(),
                                null,
                                new SkillInputSchemaNode("string", Map.of(), List.of(), null, null, List.of(), null, null, false),
                                null,
                                List.of(),
                                null,
                                null,
                                false)),
                        List.of("payload"),
                        Boolean.FALSE,
                        null,
                        List.of(),
                        null,
                        null,
                        false));

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("vendor", "Acme");
        payload.put("count", 3);

        SkillInputValidationResult result = validator.validate(Map.of("payload", payload), contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.normalizedInput()).containsKey("payload");
        assertThat(result.issues()).extracting(SkillInputValidationIssue::path).containsExactly("payload.count");
        assertThat(result.issues()).extracting(SkillInputValidationIssue::code).containsExactly("type_mismatch");
    }

    @Test
    void acceptsLongSizedIntegerStrings() {
        SkillInputContract contract = new SkillInputContract(
                SkillInputContract.SkillInputContractKind.JAVA_REFLECTED,
                new SkillInputSchemaNode(
                        "object",
                        Map.of("count", new SkillInputSchemaNode("integer", Map.of(), List.of(), null, null, List.of(), null, null, false)),
                        List.of("count"),
                        Boolean.FALSE,
                        null,
                        List.of(),
                        null,
                        null,
                        false));

        SkillInputValidationResult result = validator.validate(Map.of("count", "5000000000"), contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.normalizedInput().get("count")).isEqualTo(5_000_000_000L);
    }

    @Test
    void acceptsArraysWithoutItemsSchema() {
        SkillInputContract contract = new SkillInputContract(
                SkillInputContract.SkillInputContractKind.JAVA_REFLECTED,
                new SkillInputSchemaNode(
                        "object",
                        Map.of("values", new SkillInputSchemaNode("array", Map.of(), List.of(), null, null, List.of(), null, null, false)),
                        List.of("values"),
                        Boolean.FALSE,
                        null,
                        List.of(),
                        null,
                        null,
                        false));

        SkillInputValidationResult result = validator.validate(Map.of("values", List.of("alpha", 2, true)), contract);

        assertThat(result.valid()).isTrue();
        assertThat(result.normalizedInput().get("values")).isEqualTo(List.of("alpha", 2, true));
    }
}
