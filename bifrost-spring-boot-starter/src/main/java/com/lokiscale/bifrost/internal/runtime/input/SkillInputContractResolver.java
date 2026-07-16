package com.lokiscale.bifrost.internal.runtime.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.internal.core.SkillImplementationTarget;
import com.lokiscale.bifrost.internal.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.internal.skill.YamlSkillManifest;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    public SkillInputContract resolveYamlCapability(YamlSkillDefinition definition, @Nullable SkillImplementationTarget mappedTarget)
    {
        Objects.requireNonNull(definition, "definition must not be null");
        if (definition.hasDeclaredInputSchema())
        {
            return new SkillInputContract(
                    SkillInputContract.SkillInputContractKind.YAML_EXPLICIT,
                    fromManifest(definition.inputSchema()));
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

    public SkillInputSchemaNode fromManifest(YamlSkillManifest.InputSchemaManifest manifest)
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
                false,
                "attachment".equals(manifest.getType()),
                manifest.getMediaType(),
                manifest.getAllowedContentTypes());
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
        boolean attachment = schema.path("x-bifrost-attachment").asBoolean(false) || "attachment".equals(type);
        String attachmentMediaType = schema.path("x-bifrost-media-type").isTextual()
                ? schema.path("x-bifrost-media-type").asText()
                : null;
        List<String> allowedContentTypes = schema.path("x-bifrost-allowed-content-types").isArray()
                ? StreamSupport.stream(schema.path("x-bifrost-allowed-content-types").spliterator(), false)
                        .map(JsonNode::asText)
                        .toList()
                : List.of();

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
                runtimeRefCapable,
                attachment,
                attachmentMediaType,
                allowedContentTypes);
    }

    private Map<String, Object> toJsonSchemaNode(SkillInputSchemaNode schema)
    {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("type", schema.isAttachment() ? "string" : schema.type());

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
        if (schema.isAttachment())
        {
            jsonSchema.put("x-bifrost-attachment", true);
            jsonSchema.put("x-bifrost-media-type", schema.attachmentMediaType());
            jsonSchema.put("x-bifrost-allowed-content-types", schema.allowedContentTypes());
        }

        return jsonSchema;
    }

}
