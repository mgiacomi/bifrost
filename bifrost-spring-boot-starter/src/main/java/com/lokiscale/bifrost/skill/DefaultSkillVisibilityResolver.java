package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class DefaultSkillVisibilityResolver implements SkillVisibilityResolver {

    private final YamlSkillCatalog yamlSkillCatalog;
    private final CapabilityRegistry capabilityRegistry;

    public DefaultSkillVisibilityResolver(YamlSkillCatalog yamlSkillCatalog, CapabilityRegistry capabilityRegistry) {
        this.yamlSkillCatalog = Objects.requireNonNull(yamlSkillCatalog, "yamlSkillCatalog must not be null");
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
    }

    @Override
    public List<CapabilityMetadata> visibleSkillsFor(String currentSkillName, @Nullable Authentication authentication) {
        YamlSkillDefinition currentSkill = yamlSkillCatalog.getSkill(currentSkillName);
        if (currentSkill == null) {
            throw new IllegalArgumentException("Unknown YAML skill '" + currentSkillName + "'");
        }

        Set<String> authorities = authentication == null
                ? Set.of()
                : authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(java.util.stream.Collectors.toSet());

        LinkedHashSet<CapabilityMetadata> visible = new LinkedHashSet<>();
        for (String allowedSkillName : currentSkill.allowedSkills()) {
            if (currentSkillName.equals(allowedSkillName)) {
                continue;
            }
            YamlSkillDefinition allowedSkill = yamlSkillCatalog.getSkill(allowedSkillName);
            if (allowedSkill == null) {
                continue;
            }
            CapabilityMetadata metadata = capabilityRegistry.getCapability(allowedSkillName);
            if (metadata == null || !isAuthorized(metadata, authorities)) {
                continue;
            }
            visible.add(metadata);
        }
        return List.copyOf(visible);
    }

    private boolean isAuthorized(CapabilityMetadata metadata, Set<String> authorities) {
        return metadata.rbacRoles().isEmpty() || metadata.rbacRoles().stream().anyMatch(authorities::contains);
    }
}
