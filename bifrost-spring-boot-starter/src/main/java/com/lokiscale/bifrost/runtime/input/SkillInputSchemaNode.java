package com.lokiscale.bifrost.runtime.input;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record SkillInputSchemaNode(
        String type,
        Map<String, SkillInputSchemaNode> properties,
        List<String> required,
        Boolean additionalProperties,
        SkillInputSchemaNode additionalPropertiesSchema,
        SkillInputSchemaNode items,
        List<String> enumValues,
        String description,
        String format,
        boolean runtimeRefCapable) {

    public SkillInputSchemaNode {
        type = requireNonBlank(type, "type");
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        required = required == null ? List.of() : List.copyOf(required);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    public SkillInputSchemaNode(String type,
                                Map<String, SkillInputSchemaNode> properties,
                                List<String> required,
                                Boolean additionalProperties,
                                SkillInputSchemaNode items,
                                List<String> enumValues,
                                String description,
                                String format,
                                boolean runtimeRefCapable) {
        this(type, properties, required, additionalProperties, null, items, enumValues, description, format, runtimeRefCapable);
    }

    public boolean isObject() {
        return "object".equals(type);
    }

    public boolean isArray() {
        return "array".equals(type);
    }

    public boolean isString() {
        return "string".equals(type);
    }

    public boolean allowsAdditionalProperties() {
        return !Boolean.FALSE.equals(additionalProperties) || additionalPropertiesSchema != null;
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
