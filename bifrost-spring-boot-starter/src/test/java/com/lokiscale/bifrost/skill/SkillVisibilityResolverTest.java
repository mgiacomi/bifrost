package com.lokiscale.bifrost.skill;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.autoconfigure.BifrostModelsProperties;
import com.lokiscale.bifrost.autoconfigure.BifrostSkillProperties;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityKind;
import com.lokiscale.bifrost.core.CapabilityToolDescriptor;
import com.lokiscale.bifrost.core.InMemoryCapabilityRegistry;
import com.lokiscale.bifrost.core.ModelPreference;
import com.lokiscale.bifrost.core.SkillExecutionDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SkillVisibilityResolverTest {

    @Test
    void returnsOnlyAllowedYamlSkillsThatPassRbac() {
        YamlSkillCatalog catalog = catalog("classpath:/skills/valid/allowed-*.yaml");
        catalog.afterPropertiesSet();
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registerTargetCapabilities(registry);
        new YamlSkillCapabilityRegistrar(registry, catalog).afterSingletonsInstantiated();
        registry.register("internal.only.target", metadata("internal.only.target", Set.of()));

        DefaultSkillVisibilityResolver resolver = new DefaultSkillVisibilityResolver(catalog, registry);

        List<CapabilityMetadata> visible = resolver.visibleSkillsFor(
                "root.visible.skill",
                UsernamePasswordAuthenticationToken.authenticated(
                        "user",
                        "pw",
                        AuthorityUtils.createAuthorityList("ROLE_ALLOWED")));

        assertThat(visible).extracting(CapabilityMetadata::name).containsExactly("allowed.visible.skill");
    }

    @Test
    void excludesNonYamlCapabilitiesEvenIfListedInAllowedSkills() {
        YamlSkillCatalog catalog = catalog("classpath:/skills/valid/allowed-*.yaml");
        catalog.afterPropertiesSet();
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registerTargetCapabilities(registry);
        new YamlSkillCapabilityRegistrar(registry, catalog).afterSingletonsInstantiated();
        registry.register("internal.only.target", metadata("internal.only.target", Set.of()));

        DefaultSkillVisibilityResolver resolver = new DefaultSkillVisibilityResolver(catalog, registry);

        List<CapabilityMetadata> visible = resolver.visibleSkillsFor(
                "root.visible.skill",
                UsernamePasswordAuthenticationToken.authenticated(
                        "user",
                        "pw",
                        AuthorityUtils.createAuthorityList("ROLE_ALLOWED")));

        assertThat(visible).extracting(CapabilityMetadata::name).containsExactly("allowed.visible.skill");
    }

    private static void registerTargetCapabilities(InMemoryCapabilityRegistry registry) {
        registry.register("deterministicTarget", metadata("deterministicTarget", Set.of()));
    }

    private static YamlSkillCatalog catalog(String location) {
        BifrostModelsProperties models = new BifrostModelsProperties();
        BifrostModelsProperties.ModelCatalogEntry entry = new BifrostModelsProperties.ModelCatalogEntry();
        entry.setProvider(AiProvider.OPENAI);
        entry.setProviderModel("openai/gpt-5");
        entry.setThinkingLevels(Set.of("low", "medium", "high"));
        models.setModels(Map.of("gpt-5", entry));

        BifrostSkillProperties skills = new BifrostSkillProperties();
        skills.setLocations(List.of(location));

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new YamlSkillCatalog(models, skills, new PathMatchingResourcePatternResolver(), mapper);
    }

    private static CapabilityMetadata metadata(String name, Set<String> roles) {
        return new CapabilityMetadata(
                targetId(name),
                name,
                "desc",
                ModelPreference.LIGHT,
                new SkillExecutionDescriptor("gpt-5", AiProvider.OPENAI, "openai/gpt-5", "medium"),
                roles,
                arguments -> "ok",
                "deterministicTarget".equals(name) ? CapabilityKind.JAVA_METHOD : CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic(name, "desc"),
                "deterministicTarget".equals(name) ? null : targetId(name));
    }

    private static String targetId(String name) {
        return "deterministicTarget".equals(name) ? "targetBean#deterministicTarget" : "yaml:" + name;
    }
}
