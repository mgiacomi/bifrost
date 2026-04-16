package com.lokiscale.bifrost.runtime.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

public class SkillInputContractResolver
{
    private final ObjectMapper objectMapper;

    public SkillInputContractResolver()
    {
        this(new ObjectMapper());
    }

    public SkillInputContractResolver(ObjectMapper objectMapper)
    {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public SkillInputContract resolveJavaCapability(String inputSchema)
    {
        return new SkillInputContract(
                SkillInputContract.SkillInputContractKind.JAVA_REFLECTED,
                fromJsonSchema(inputSchema));
    }

    public SkillInputContract resolveYamlCapability(YamlSkillDefinition definition, @Nullable CapabilityMetadata mappedTarget)
    {
        Objects.requireNonNull(definition, "definition must not be null");
        if (definition.hasDeclaredInputSchema())
        {
            SkillInputSchemaNode explicitSchema = fromManifest(definition.inputSchema());
            if (mappedTarget != null)
            {
                explicitSchema = mergeRuntimeMarkers(mappedTarget.inputContract().schema(), explicitSchema);
            }
            return new SkillInputContract(
                    SkillInputContract.SkillInputContractKind.YAML_EXPLICIT,
                    explicitSchema);
        }

        if (mappedTarget != null)
        {
            return new SkillInputContract(
                    SkillInputContract.SkillInputContractKind.YAML_INHERITED,
                    mappedTarget.inputContract().schema());
        }

        return SkillInputContract.genericObject();
    }

    public SkillInputContract resolveFromToolSchema(String inputSchema)
    {
        SkillInputSchemaNode schemaNode = fromJsonSchema(inputSchema);
        boolean generic = schemaNode.isObject()
                && schemaNode.properties().isEmpty()
                && schemaNode.required().isEmpty()
                && !Boolean.FALSE.equals(schemaNode.additionalProperties())
                && schemaNode.additionalPropertiesSchema() == null;

        return generic
                ? SkillInputContract.genericObject()
                : new SkillInputContract(SkillInputContract.SkillInputContractKind.JAVA_REFLECTED, schemaNode);
    }

    public String toJsonSchema(SkillInputContract contract)
    {
        Objects.requireNonNull(contract, "contract must not be null");
        try
        {
            return objectMapper.writeValueAsString(toJsonSchemaNode(contract.schema()));
        }
        catch (IOException ex)
        {
            throw new IllegalStateException("Failed to serialize capability input schema", ex);
        }
    }

    public SkillInputSchemaNode fromManifest(YamlSkillManifest.OutputSchemaManifest manifest)
    {
        Objects.requireNonNull(manifest, "manifest must not be null");
        Map<String, SkillInputSchemaNode> properties = new LinkedHashMap<>();
        manifest.getProperties().forEach((name, child) -> properties.put(name, fromManifest(child)));

        return new SkillInputSchemaNode(
                manifest.getType(),
                properties,
                manifest.getRequired(),
                manifest.getAdditionalProperties(),
                null,
                manifest.getItems() == null ? null : fromManifest(manifest.getItems()),
                manifest.getEnumValues(),
                manifest.getDescription(),
                manifest.getFormat(),
                false);
    }

    public SkillInputSchemaNode fromJsonSchema(String inputSchema)
    {
        if (!StringUtils.hasText(inputSchema))
        {
            return SkillInputContract.genericObject().schema();
        }
        try
        {
            return fromJsonNode(objectMapper.readTree(inputSchema));
        }
        catch (IOException ex)
        {
            throw new IllegalStateException("Failed to parse capability input schema", ex);
        }
    }

    public void validateStructuralCompatibility(SkillInputSchemaNode expected,
            SkillInputSchemaNode actual,
            String fieldPath)
    {
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(actual, "actual must not be null");
        if (!Objects.equals(expected.type(), actual.type()))
        {
            throw mismatch(fieldPath, "type mismatch: expected '%s' but found '%s'".formatted(expected.type(), actual.type()));
        }
        if (!new TreeSet<>(expected.required()).equals(new TreeSet<>(actual.required())))
        {
            throw mismatch(fieldPath, "required fields mismatch: expected %s but found %s"
                    .formatted(expected.required(), actual.required()));
        }
        if (allowsAdditionalPropertiesSemantically(expected) != allowsAdditionalPropertiesSemantically(actual))
        {
            throw mismatch(fieldPath, "additionalProperties mismatch: expected %s but found %s"
                    .formatted(expected.additionalProperties(), actual.additionalProperties()));
        }
        if (expected.additionalPropertiesSchema() != null || actual.additionalPropertiesSchema() != null)
        {
            if (expected.additionalPropertiesSchema() == null || actual.additionalPropertiesSchema() == null)
            {
                throw mismatch(fieldPath + ".additionalProperties", "additionalProperties schema mismatch");
            }
            validateStructuralCompatibility(
                    expected.additionalPropertiesSchema(),
                    actual.additionalPropertiesSchema(),
                    fieldPath + ".additionalProperties");
        }
        if (!Objects.equals(new TreeSet<>(expected.enumValues()), new TreeSet<>(actual.enumValues())))
        {
            throw mismatch(fieldPath, "enum mismatch: expected %s but found %s"
                    .formatted(expected.enumValues(), actual.enumValues()));
        }
        if (!Objects.equals(expected.format(), actual.format()))
        {
            throw mismatch(fieldPath, "format mismatch: expected %s but found %s"
                    .formatted(expected.format(), actual.format()));
        }
        if (!new TreeSet<>(expected.properties().keySet()).equals(new TreeSet<>(actual.properties().keySet())))
        {
            throw mismatch(fieldPath, "properties mismatch: expected %s but found %s"
                    .formatted(expected.properties().keySet(), actual.properties().keySet()));
        }
        for (String propertyName : expected.properties().keySet())
        {
            validateStructuralCompatibility(
                    expected.properties().get(propertyName),
                    actual.properties().get(propertyName),
                    fieldPath + ".properties." + propertyName);
        }
        if (expected.items() != null || actual.items() != null)
        {
            if (expected.items() == null || actual.items() == null)
            {
                throw mismatch(fieldPath + ".items", "items mismatch");
            }
            validateStructuralCompatibility(expected.items(), actual.items(), fieldPath + ".items");
        }
    }

    private SkillInputSchemaNode fromJsonNode(JsonNode schema)
    {
        String type = schema.path("type").asText("object");
        Map<String, SkillInputSchemaNode> properties = new LinkedHashMap<>();
        JsonNode propertiesNode = schema.path("properties");
        if (propertiesNode.isObject())
        {
            Iterator<Map.Entry<String, JsonNode>> fields = propertiesNode.fields();
            while (fields.hasNext())
            {
                Map.Entry<String, JsonNode> field = fields.next();
                properties.put(field.getKey(), fromJsonNode(field.getValue()));
            }
        }

        List<String> required = schema.path("required").isArray()
                ? StreamSupport.stream(schema.path("required").spliterator(), false)
                        .map(JsonNode::asText)
                        .toList()
                : List.of();

        List<String> enumValues = schema.path("enum").isArray()
                ? StreamSupport.stream(schema.path("enum").spliterator(), false)
                        .map(JsonNode::asText)
                        .toList()
                : List.of();

        JsonNode additionalPropertiesNode = schema.get("additionalProperties");
        Boolean additionalProperties = null;
        SkillInputSchemaNode additionalPropertiesSchema = null;
        if (additionalPropertiesNode != null && !additionalPropertiesNode.isNull())
        {
            if (additionalPropertiesNode.isBoolean())
            {
                additionalProperties = additionalPropertiesNode.asBoolean();
            }
            else if (additionalPropertiesNode.isObject())
            {
                additionalPropertiesSchema = fromJsonNode(additionalPropertiesNode);
            }
        }

        SkillInputSchemaNode items = schema.has("items") && !schema.get("items").isNull()
                ? fromJsonNode(schema.get("items"))
                : null;

        String description = schema.path("description").isTextual() ? schema.path("description").asText() : null;
        String format = schema.path("format").isTextual() ? schema.path("format").asText() : null;
        boolean runtimeRefCapable = schema.path("x-bifrost-runtime-ref-capable").asBoolean(false);

        return new SkillInputSchemaNode(
                type,
                properties,
                required,
                additionalProperties,
                additionalPropertiesSchema,
                items,
                enumValues,
                description,
                format,
                runtimeRefCapable);
    }

    private IllegalStateException mismatch(String fieldPath, String detail)
    {
        return new IllegalStateException("input_schema compatibility failed at '" + fieldPath + "': " + detail);
    }

    private boolean allowsAdditionalPropertiesSemantically(SkillInputSchemaNode schema)
    {
        return schema != null && schema.allowsAdditionalProperties();
    }

    private Map<String, Object> toJsonSchemaNode(SkillInputSchemaNode schema)
    {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("type", schema.type());

        if (!schema.properties().isEmpty())
        {
            Map<String, Object> properties = new LinkedHashMap<>();
            schema.properties().forEach((name, child) -> properties.put(name, toJsonSchemaNode(child)));
            jsonSchema.put("properties", properties);
        }
        if (!schema.required().isEmpty())
        {
            jsonSchema.put("required", schema.required());
        }
        if (schema.additionalProperties() != null)
        {
            jsonSchema.put("additionalProperties", schema.additionalProperties());
        }
        if (schema.additionalPropertiesSchema() != null)
        {
            jsonSchema.put("additionalProperties", toJsonSchemaNode(schema.additionalPropertiesSchema()));
        }
        if (schema.items() != null)
        {
            jsonSchema.put("items", toJsonSchemaNode(schema.items()));
        }
        if (!schema.enumValues().isEmpty())
        {
            jsonSchema.put("enum", schema.enumValues());
        }
        if (schema.description() != null && !schema.description().isBlank())
        {
            jsonSchema.put("description", schema.description());
        }
        if (schema.format() != null && !schema.format().isBlank())
        {
            jsonSchema.put("format", schema.format());
        }
        if (schema.runtimeRefCapable())
        {
            jsonSchema.put("x-bifrost-runtime-ref-capable", true);
        }

        return jsonSchema;
    }

    private SkillInputSchemaNode mergeRuntimeMarkers(SkillInputSchemaNode source, SkillInputSchemaNode target)
    {
        if (source == null || target == null)
        {
            return target;
        }

        Map<String, SkillInputSchemaNode> mergedProperties = new LinkedHashMap<>();
        target.properties().forEach((name, child) -> mergedProperties.put(
                name,
                mergeRuntimeMarkers(source.properties().get(name), child)));

        SkillInputSchemaNode mergedItems = target.items() == null
                ? null
                : mergeRuntimeMarkers(source.items(), target.items());

        return new SkillInputSchemaNode(
                target.type(),
                mergedProperties,
                target.required(),
                target.additionalProperties(),
                mergeRuntimeMarkers(source.additionalPropertiesSchema(), target.additionalPropertiesSchema()),
                mergedItems,
                target.enumValues(),
                target.description(),
                target.format(),
                source.runtimeRefCapable());
    }
}
