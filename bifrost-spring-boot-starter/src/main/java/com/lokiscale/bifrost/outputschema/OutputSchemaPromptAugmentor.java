package com.lokiscale.bifrost.outputschema;

import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OutputSchemaPromptAugmentor
{
    public Prompt augment(Prompt prompt, YamlSkillManifest.OutputSchemaManifest schema)
    {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        String guidance = """
                Return JSON only.
                Do not include markdown fences, commentary, or prose.
                Use the configured field names exactly.
                Omit unknown fields unless they are explicitly allowed.

                Output schema summary:
                %s
                """.formatted(describeObject(schema).stripTrailing()).stripTrailing();

        return prompt.augmentSystemMessage(systemMessage -> systemMessage.mutate()
                .text(joinSystemText(systemMessage.getText(), guidance))
                .build());
    }

    private String describeObject(YamlSkillManifest.OutputSchemaManifest schema)
    {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, YamlSkillManifest.OutputSchemaManifest> entry : schema.getProperties().entrySet())
        {
            boolean required = schema.getRequired().contains(entry.getKey());
            lines.add("- " + entry.getKey() + ": " + describeType(entry.getValue()) + (required ? " (required)" : ""));
        }
        return lines.isEmpty() ? "- return an empty JSON object" : String.join("\n", lines);
    }

    private String describeType(YamlSkillManifest.OutputSchemaManifest schema)
    {
        StringBuilder builder = new StringBuilder(schema.getType());
        if ("array".equals(schema.getType()) && schema.getItems() != null)
        {
            builder.append(" of ").append(describeType(schema.getItems()));
        }
        if ("object".equals(schema.getType()) && !schema.getProperties().isEmpty())
        {
            builder.append(" {");
            builder.append(String.join(", ", schema.getProperties().keySet()));
            builder.append("}");
        }
        if (!schema.getEnumValues().isEmpty())
        {
            builder.append(" enum ").append(schema.getEnumValues());
        }
        if (StringUtils.hasText(schema.getFormat()))
        {
            builder.append(" format ").append(schema.getFormat());
        }
        if (StringUtils.hasText(schema.getDescription()))
        {
            builder.append(" - ").append(schema.getDescription());
        }
        return builder.toString();
    }

    private String joinSystemText(String original, String hint)
    {
        if (!StringUtils.hasText(original))
        {
            return hint;
        }
        return original + "\n\n" + hint;
    }
}
