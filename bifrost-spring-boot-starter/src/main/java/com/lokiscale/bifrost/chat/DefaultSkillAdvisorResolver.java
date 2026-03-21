package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.linter.LinterCallAdvisor;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class DefaultSkillAdvisorResolver implements SkillAdvisorResolver {

    private final ExecutionStateService executionStateService;

    public DefaultSkillAdvisorResolver(ExecutionStateService executionStateService) {
        this.executionStateService = Objects.requireNonNull(
                executionStateService,
                "executionStateService must not be null");
    }

    @Override
    public List<Advisor> resolve(YamlSkillDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        YamlSkillManifest.LinterManifest linter = definition.linter();
        if (linter == null) {
            return List.of();
        }
        if (!"regex".equals(linter.getType()) || linter.getRegex() == null) {
            return List.of();
        }
        String detail = linter.getRegex().getMessage();
        if (detail == null || detail.isBlank()) {
            detail = "Return output that matches the configured regex linter.";
        }
        return List.of(new LinterCallAdvisor(
                definition.manifest().getName(),
                linter.getType(),
                Pattern.compile(linter.getRegex().getPattern()),
                detail,
                linter.getMaxRetries(),
                outcome -> executionStateService.recordLinterOutcome(BifrostSession.getCurrentSession(), outcome)));
    }
}
