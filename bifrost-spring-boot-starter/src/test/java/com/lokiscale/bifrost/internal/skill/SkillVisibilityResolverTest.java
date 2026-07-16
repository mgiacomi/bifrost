package com.lokiscale.bifrost.internal.skill;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.lokiscale.bifrost.autoconfigure.AiDriver;
import com.lokiscale.bifrost.internal.core.BifrostSession;
import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import com.lokiscale.bifrost.autoconfigure.BifrostProperties;
import com.lokiscale.bifrost.internal.core.CapabilityMetadata;
import com.lokiscale.bifrost.internal.core.InMemoryCapabilityRegistry;
import com.lokiscale.bifrost.internal.core.InMemorySkillImplementationTargetRegistry;
import com.lokiscale.bifrost.internal.core.SkillImplementationTarget;
import com.lokiscale.bifrost.internal.runtime.input.SkillInputContract;
import com.lokiscale.bifrost.internal.runtime.input.SkillInputContractResolver;
import com.lokiscale.bifrost.internal.security.DefaultAccessGuard;
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
        InMemorySkillImplementationTargetRegistry targets = targetRegistry();
        new YamlSkillCapabilityRegistrar(registry, targets, catalog, new SkillInputContractResolver()).afterSingletonsInstantiated();

        DefaultSkillVisibilityResolver resolver = new DefaultSkillVisibilityResolver(catalog, registry, new DefaultAccessGuard());

        List<CapabilityMetadata> visible = resolver.visibleSkillsFor(
                "rootVisibleSkill",
                com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-1", 2),
                UsernamePasswordAuthenticationToken.authenticated(
                        "user",
                        "pw",
                        AuthorityUtils.createAuthorityList("ROLE_ALLOWED")));

        assertThat(visible).extracting(CapabilityMetadata::name).containsExactly("allowedVisibleSkill");
    }

    @Test
    void doesNotExposeImplementationTargetIdsAsAllowedChildren() {
        YamlSkillCatalog catalog = catalog("classpath:/skills/valid/allowed-*.yaml");
        catalog.afterPropertiesSet();
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        InMemorySkillImplementationTargetRegistry targets = targetRegistry();
        assertThat(targets.getTarget("targetBean#deterministicTarget")).isNotNull();
        new YamlSkillCapabilityRegistrar(registry, targets, catalog, new SkillInputContractResolver()).afterSingletonsInstantiated();

        DefaultSkillVisibilityResolver resolver = new DefaultSkillVisibilityResolver(catalog, registry, new DefaultAccessGuard());

        List<CapabilityMetadata> visible = resolver.visibleSkillsFor(
                "rootVisibleSkill",
                com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-1", 2),
                UsernamePasswordAuthenticationToken.authenticated(
                        "user",
                        "pw",
                        AuthorityUtils.createAuthorityList("ROLE_ALLOWED")));

        assertThat(visible).extracting(CapabilityMetadata::name).containsExactly("allowedVisibleSkill");
    }

    @Test
    void hidesProtectedSkillsWhenAuthenticationIsMissing() {
        YamlSkillCatalog catalog = catalog("classpath:/skills/valid/allowed-*.yaml");
        catalog.afterPropertiesSet();
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        InMemorySkillImplementationTargetRegistry targets = targetRegistry();
        new YamlSkillCapabilityRegistrar(registry, targets, catalog, new SkillInputContractResolver()).afterSingletonsInstantiated();

        DefaultSkillVisibilityResolver resolver = new DefaultSkillVisibilityResolver(catalog, registry, new DefaultAccessGuard());

        List<CapabilityMetadata> visible = resolver.visibleSkillsFor(
                "rootVisibleSkill",
                com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-1", 2),
                null);

        assertThat(visible).isEmpty();
    }

    @Test
    void usesSessionFallbackForProtectedSkillVisibility() {
        YamlSkillCatalog catalog = catalog("classpath:/skills/valid/allowed-*.yaml");
        catalog.afterPropertiesSet();
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        InMemorySkillImplementationTargetRegistry targets = targetRegistry();
        new YamlSkillCapabilityRegistrar(registry, targets, catalog, new SkillInputContractResolver()).afterSingletonsInstantiated();

        DefaultSkillVisibilityResolver resolver = new DefaultSkillVisibilityResolver(catalog, registry, new DefaultAccessGuard());
        BifrostSession session = com.lokiscale.bifrost.internal.core.TestBifrostSessions.withId("session-1", 2);
        session.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "user",
                "pw",
                AuthorityUtils.createAuthorityList("ROLE_ALLOWED")));

        List<CapabilityMetadata> visible = resolver.visibleSkillsFor("rootVisibleSkill", session, null);

        assertThat(visible).extracting(CapabilityMetadata::name).containsExactly("allowedVisibleSkill");
    }

    private static InMemorySkillImplementationTargetRegistry targetRegistry() {
        InMemorySkillImplementationTargetRegistry registry = new InMemorySkillImplementationTargetRegistry();
        registry.register(new SkillImplementationTarget(
                "targetBean#deterministicTarget",
                "desc",
                arguments -> "ok",
                "{\"type\":\"object\"}",
                SkillInputContract.genericObject()));
        return registry;
    }

    private static YamlSkillCatalog catalog(String location) {
        BifrostProperties models = new BifrostProperties();
        BifrostProperties.ConnectionProperties connection = new BifrostProperties.ConnectionProperties();
        connection.setDriver(AiDriver.OPENAI);
        connection.setApiKey("test-key");
        models.setConnections(Map.of("openai-main", connection));
        BifrostProperties.ModelCatalogEntry entry = new BifrostProperties.ModelCatalogEntry();
        entry.setConnection("openai-main");
        entry.setProviderModel("openai/gpt-5");
        entry.setThinkingLevels(Set.of("low", "medium", "high"));
        models.setModels(Map.of("gpt-5", entry));

        BifrostProperties.Skills skills = new BifrostProperties.Skills();
        skills.setLocations(List.of(location));

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new YamlSkillCatalog(models, skills, new PathMatchingResourcePatternResolver(), mapper);
    }

}
