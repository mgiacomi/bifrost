package com.lokiscale.bifrost.skill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = false)
public class YamlSkillManifest {

    private String name;
    private String description;
    private String model;

    @JsonProperty("thinking_level")
    private String thinkingLevel;

    @JsonProperty("allowed_skills")
    private List<String> allowedSkills = List.of();

    @JsonProperty("rbac_roles")
    private List<String> rbacRoles = List.of();

    @JsonProperty("planning_mode")
    private Boolean planningMode;

    @JsonProperty("max_steps")
    private Integer maxSteps;

    private LinterManifest linter;

    @JsonProperty("output_schema")
    private OutputSchemaManifest outputSchema;

    @JsonProperty("output_schema_max_retries")
    private Integer outputSchemaMaxRetries;

    @JsonProperty("evidence_contract")
    private EvidenceContractManifest evidenceContract;

    private MappingManifest mapping = new MappingManifest();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getThinkingLevel() {
        return thinkingLevel;
    }

    public void setThinkingLevel(String thinkingLevel) {
        this.thinkingLevel = thinkingLevel;
    }

    public List<String> getAllowedSkills() {
        return allowedSkills;
    }

    public void setAllowedSkills(List<String> allowedSkills) {
        this.allowedSkills = allowedSkills == null ? List.of() : List.copyOf(allowedSkills);
    }

    public List<String> getRbacRoles() {
        return rbacRoles;
    }

    public void setRbacRoles(List<String> rbacRoles) {
        this.rbacRoles = rbacRoles == null ? List.of() : List.copyOf(rbacRoles);
    }

    public Boolean getPlanningMode() {
        return planningMode;
    }

    public void setPlanningMode(Boolean planningMode) {
        this.planningMode = planningMode;
    }

    public Integer getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(Integer maxSteps) {
        this.maxSteps = maxSteps;
    }

    public LinterManifest getLinter() {
        return linter;
    }

    public void setLinter(LinterManifest linter) {
        this.linter = linter;
    }

    public MappingManifest getMapping() {
        return mapping;
    }

    public void setMapping(MappingManifest mapping) {
        this.mapping = mapping == null ? new MappingManifest() : mapping;
    }

    public OutputSchemaManifest getOutputSchema() {
        return outputSchema;
    }

    public void setOutputSchema(OutputSchemaManifest outputSchema) {
        this.outputSchema = outputSchema;
    }

    public Integer getOutputSchemaMaxRetries() {
        return outputSchemaMaxRetries;
    }

    public void setOutputSchemaMaxRetries(Integer outputSchemaMaxRetries) {
        this.outputSchemaMaxRetries = outputSchemaMaxRetries;
    }

    public EvidenceContractManifest getEvidenceContract() {
        return evidenceContract;
    }

    public void setEvidenceContract(EvidenceContractManifest evidenceContract) {
        this.evidenceContract = evidenceContract;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class LinterManifest {

        // ENG-016 supports regex linting only; future modes can be added here as siblings.
        private String type;

        @JsonProperty("max_retries")
        private Integer maxRetries;

        private RegexManifest regex;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = StringUtils.hasText(type) ? type.trim().toLowerCase(Locale.ROOT) : type;
        }

        public Integer getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }

        public RegexManifest getRegex() {
            return regex;
        }

        public void setRegex(RegexManifest regex) {
            this.regex = regex;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class RegexManifest {

        private String pattern;
        private String message;

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = StringUtils.hasText(pattern) ? pattern.trim() : pattern;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = StringUtils.hasText(message) ? message : null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class MappingManifest {

        @JsonProperty("target_id")
        private String targetId;

        public String getTargetId() {
            return targetId;
        }

        public void setTargetId(String targetId) {
            this.targetId = targetId;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class EvidenceContractManifest {

        private Map<String, List<String>> claims = Map.of();

        @JsonProperty("tool_evidence")
        private Map<String, List<String>> toolEvidence = Map.of();

        public Map<String, List<String>> getClaims() {
            return claims;
        }

        public void setClaims(Map<String, List<String>> claims) {
            this.claims = normalizeStringListMap(claims);
        }

        public Map<String, List<String>> getToolEvidence() {
            return toolEvidence;
        }

        public void setToolEvidence(Map<String, List<String>> toolEvidence) {
            this.toolEvidence = normalizeStringListMap(toolEvidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class OutputSchemaManifest {

        private String type;
        private Map<String, OutputSchemaManifest> properties = Map.of();
        private List<String> required = List.of();
        private Boolean additionalProperties;
        private OutputSchemaManifest items;

        @JsonProperty("enum")
        private List<String> enumValues = List.of();

        private String description;
        private String format;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = StringUtils.hasText(type) ? type.trim().toLowerCase(Locale.ROOT) : type;
        }

        public Map<String, OutputSchemaManifest> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, OutputSchemaManifest> properties) {
            if (properties == null || properties.isEmpty()) {
                this.properties = Map.of();
                return;
            }
            this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }

        public List<String> getRequired() {
            return required;
        }

        public void setRequired(List<String> required) {
            this.required = required == null ? List.of() : List.copyOf(required);
        }

        public Boolean getAdditionalProperties() {
            return additionalProperties;
        }

        public void setAdditionalProperties(Boolean additionalProperties) {
            this.additionalProperties = additionalProperties;
        }

        public OutputSchemaManifest getItems() {
            return items;
        }

        public void setItems(OutputSchemaManifest items) {
            this.items = items;
        }

        public List<String> getEnumValues() {
            return enumValues;
        }

        public void setEnumValues(List<String> enumValues) {
            this.enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = StringUtils.hasText(description) ? description.trim() : null;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = StringUtils.hasText(format) ? format.trim() : null;
        }
    }

    private static Map<String, List<String>> normalizeStringListMap(Map<String, List<String>> rawMap) {
        if (rawMap == null || rawMap.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<String>> normalized = new LinkedHashMap<>();
        rawMap.forEach((key, values) -> normalized.put(
                key == null ? null : key.trim(),
                normalizeStringList(values)));
        return Collections.unmodifiableMap(normalized);
    }

    private static List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> value == null ? null : value.trim())
                .toList();
    }
}
