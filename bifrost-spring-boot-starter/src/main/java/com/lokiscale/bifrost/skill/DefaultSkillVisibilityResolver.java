package com.lokiscale.bifrost.skill;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.security.AccessGuard;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public class DefaultSkillVisibilityResolver implements SkillVisibilityResolver {

    private final YamlSkillCatalog yamlSkillCatalog;
    private final CapabilityRegistry capabilityRegistry;
    private final AccessGuard accessGuard;

    public DefaultSkillVisibilityResolver(YamlSkillCatalog yamlSkillCatalog,
                                          CapabilityRegistry capabilityRegistry,
                                          AccessGuard accessGuard) {
        this.yamlSkillCatalog = Objects.requireNonNull(yamlSkillCatalog, "yamlSkillCatalog must not be null");
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
        this.accessGuard = Objects.requireNonNull(accessGuard, "accessGuard must not be null");
    }

    @Override
    public List<CapabilityMetadata> visibleSkillsFor(String currentSkillName,
                                                     BifrostSession session,
                                                     @Nullable Authentication authentication) {
        YamlSkillDefinition currentSkill = yamlSkillCatalog.getSkill(currentSkillName);
        if (currentSkill == null) {
            throw new IllegalArgumentException("Unknown YAML skill '" + currentSkillName + "'");
        }

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
            if (metadata == null || !accessGuard.canAccess(metadata, session, authentication)) {
                continue;
            }
            visible.add(metadata);
        }
        return List.copyOf(visible);
    }
}
