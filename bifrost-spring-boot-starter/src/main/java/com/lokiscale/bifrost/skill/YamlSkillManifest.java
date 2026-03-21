package com.lokiscale.bifrost.skill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.util.StringUtils;

import java.util.List;
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

    private LinterManifest linter;

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
}
