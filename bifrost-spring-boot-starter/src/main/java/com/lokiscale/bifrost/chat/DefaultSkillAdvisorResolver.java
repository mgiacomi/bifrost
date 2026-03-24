package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.linter.LinterCallAdvisor;
import com.lokiscale.bifrost.outputschema.OutputSchemaCallAdvisor;
import com.lokiscale.bifrost.outputschema.OutputSchemaPromptAugmentor;
import com.lokiscale.bifrost.outputschema.OutputSchemaValidator;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class DefaultSkillAdvisorResolver implements SkillAdvisorResolver {

    private final ExecutionStateService executionStateService;
    private final OutputSchemaValidator outputSchemaValidator;
    private final OutputSchemaPromptAugmentor outputSchemaPromptAugmentor;

    public DefaultSkillAdvisorResolver(ExecutionStateService executionStateService) {
        this(executionStateService, new OutputSchemaValidator(), new OutputSchemaPromptAugmentor());
    }

    DefaultSkillAdvisorResolver(ExecutionStateService executionStateService,
                                OutputSchemaValidator outputSchemaValidator,
                                OutputSchemaPromptAugmentor outputSchemaPromptAugmentor) {
        this.executionStateService = Objects.requireNonNull(
                executionStateService,
                "executionStateService must not be null");
        this.outputSchemaValidator = Objects.requireNonNull(outputSchemaValidator, "outputSchemaValidator must not be null");
        this.outputSchemaPromptAugmentor = Objects.requireNonNull(outputSchemaPromptAugmentor, "outputSchemaPromptAugmentor must not be null");
    }

    @Override
    public List<Advisor> resolve(YamlSkillDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        List<Advisor> advisors = new ArrayList<>();

        if (definition.outputSchema() != null) {
            advisors.add(new OutputSchemaCallAdvisor(
                    definition.manifest().getName(),
                    definition.outputSchema(),
                    outputSchemaValidator,
                    outputSchemaPromptAugmentor,
                    definition.outputSchemaMaxRetries(),
                    outcome -> executionStateService.recordOutputSchemaOutcome(BifrostSession.getCurrentSession(), outcome)));
        }

        YamlSkillManifest.LinterManifest linter = definition.linter();
        if (linter != null && "regex".equals(linter.getType()) && linter.getRegex() != null) {
            String detail = linter.getRegex().getMessage();
            if (detail == null || detail.isBlank()) {
                detail = "Return output that matches the configured regex linter.";
            }
            advisors.add(new LinterCallAdvisor(
                    definition.manifest().getName(),
                    linter.getType(),
                    Pattern.compile(linter.getRegex().getPattern()),
                    detail,
                    linter.getMaxRetries(),
                    outcome -> executionStateService.recordLinterOutcome(BifrostSession.getCurrentSession(), outcome)));
        }
        return List.copyOf(advisors);
    }
}
