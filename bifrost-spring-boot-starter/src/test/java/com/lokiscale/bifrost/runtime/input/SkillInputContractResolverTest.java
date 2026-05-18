package com.lokiscale.bifrost.runtime.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillInputContractResolverTest
{
    private final SkillInputContractResolver resolver = new SkillInputContractResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesAttachmentMetadataInInputContractAndJsonSchema() throws Exception
    {
        SkillInputSchemaNode schema = resolver.fromManifest(attachmentInputManifest());

        SkillInputSchemaNode image = schema.properties().get("image");
        assertThat(image.isAttachment()).isTrue();
        assertThat(image.attachmentMediaType()).isEqualTo("image");
        assertThat(image.allowedContentTypes()).containsExactly("image/jpeg");

        String jsonSchema = resolver.toJsonSchema(
                new SkillInputContract(SkillInputContract.SkillInputContractKind.YAML_EXPLICIT, schema));
        JsonNode imageNode = objectMapper.readTree(jsonSchema).path("properties").path("image");
        assertThat(imageNode.path("type").asText()).isEqualTo("string");
        assertThat(imageNode.path("x-bifrost-attachment").asBoolean()).isTrue();
        assertThat(imageNode.path("x-bifrost-media-type").asText()).isEqualTo("image");
        assertThat(imageNode.path("x-bifrost-allowed-content-types").get(0).asText()).isEqualTo("image/jpeg");
    }

    @Test
    void structuralCompatibilityIncludesAttachmentMetadata()
    {
        SkillInputSchemaNode expected = resolver.fromManifest(attachmentInputManifest());
        YamlSkillManifest.InputSchemaManifest actualManifest = attachmentInputManifest();
        actualManifest.getProperties().get("image").setAllowedContentTypes(List.of("image/png"));

        assertThatThrownBy(() -> resolver.validateStructuralCompatibility(
                expected,
                resolver.fromManifest(actualManifest),
                "input_schema"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attachment allowed content types mismatch");
    }

    static YamlSkillManifest.InputSchemaManifest attachmentInputManifest()
    {
        YamlSkillManifest.InputSchemaManifest root = new YamlSkillManifest.InputSchemaManifest();
        root.setType("object");
        root.setRequired(List.of("image"));
        root.setAdditionalProperties(false);

        YamlSkillManifest.InputSchemaManifest image = new YamlSkillManifest.InputSchemaManifest();
        image.setType("attachment");
        image.setMediaType("image");
        image.setAllowedContentTypes(List.of("image/jpeg"));
        image.setDescription("Ticket image");
        root.setProperties(Map.of("image", image));
        return root;
    }
}
