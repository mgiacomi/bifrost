package com.lokiscale.bifrost.chat;

import com.lokiscale.bifrost.core.AdvisorTraceRecorder;
import com.lokiscale.bifrost.core.AdvisorTraceContext;
import com.lokiscale.bifrost.core.AdvisorTraceFact;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.linter.LinterCallAdvisor;
import com.lokiscale.bifrost.outputschema.OutputSchemaCallAdvisor;
import com.lokiscale.bifrost.outputschema.OutputSchemaPromptAugmentor;
import com.lokiscale.bifrost.outputschema.OutputSchemaValidator;
import com.lokiscale.bifrost.runtime.evidence.EvidenceBackedOutputValidator;
import com.lokiscale.bifrost.runtime.evidence.EvidenceContractCallAdvisor;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class DefaultSkillAdvisorResolver implements SkillAdvisorResolver
{
    private final ExecutionStateService executionStateService;
    private final OutputSchemaValidator outputSchemaValidator;
    private final OutputSchemaPromptAugmentor outputSchemaPromptAugmentor;
    private final EvidenceBackedOutputValidator evidenceBackedOutputValidator;

    public DefaultSkillAdvisorResolver(ExecutionStateService executionStateService)
    {
        this(executionStateService, new OutputSchemaValidator(), new OutputSchemaPromptAugmentor(), new EvidenceBackedOutputValidator());
    }

    DefaultSkillAdvisorResolver(ExecutionStateService executionStateService,
            OutputSchemaValidator outputSchemaValidator,
            OutputSchemaPromptAugmentor outputSchemaPromptAugmentor,
            EvidenceBackedOutputValidator evidenceBackedOutputValidator)
    {
        this.executionStateService = Objects.requireNonNull(
                executionStateService,
                "executionStateService must not be null");

        this.outputSchemaValidator = Objects.requireNonNull(outputSchemaValidator, "outputSchemaValidator must not be null");
        this.outputSchemaPromptAugmentor = Objects.requireNonNull(outputSchemaPromptAugmentor, "outputSchemaPromptAugmentor must not be null");
        this.evidenceBackedOutputValidator = Objects.requireNonNull(
                evidenceBackedOutputValidator,
                "evidenceBackedOutputValidator must not be null");
    }

    @Override
    public List<Advisor> resolve(YamlSkillDefinition definition)
    {
        Objects.requireNonNull(definition, "definition must not be null");
        List<Advisor> advisors = new ArrayList<>();
        AdvisorTraceRecorder advisorTraceRecorder = fact ->
        {
            if (fact == null)
            {
                return;
            }

            AdvisorTraceContext context = fact.context();
            Map<String, Object> payload = mutationPayload(fact);

            if (payload.isEmpty())
            {
                payload = Map.of("kind", fact.kind().name().toLowerCase(Locale.ROOT));
            }

            BifrostSession session;
            try
            {
                session = BifrostSession.getCurrentSession();
            }
            catch (IllegalStateException ignored)
            {
                return;
            }
            if (fact.direction() == AdvisorTraceFact.Direction.REQUEST)
            {
                executionStateService.recordAdvisorRequestMutation(session, context, payload);
            }
            else
            {
                executionStateService.recordAdvisorResponseMutation(session, context, payload);
            }
        };

        if (definition.outputSchema() != null)
        {
            advisors.add(new OutputSchemaCallAdvisor(
                    definition.manifest().getName(),
                    definition.outputSchema(),
                    outputSchemaValidator,
                    outputSchemaPromptAugmentor,
                    definition.outputSchemaMaxRetries(),
                    outcome -> executionStateService.recordOutputSchemaOutcome(BifrostSession.getCurrentSession(), outcome),
                    advisorTraceRecorder));
        }

        if (!definition.evidenceContract().isEmpty())
        {
            advisors.add(new EvidenceContractCallAdvisor(
                    definition.manifest().getName(),
                    definition.evidenceContract(),
                    evidenceBackedOutputValidator,
                    definition.outputSchemaMaxRetries(),
                    result -> executionStateService.recordEvidenceValidation(
                            BifrostSession.getCurrentSession(),
                            true,
                            Map.of(
                                    "skillName", definition.manifest().getName(),
                                    "claims", result.evaluatedClaims(),
                                    "evidence", result.availableEvidence()),
                            result),
                    result -> executionStateService.recordEvidenceValidation(
                            BifrostSession.getCurrentSession(),
                            false,
                            Map.of(
                                    "skillName", definition.manifest().getName(),
                                    "claims", result.evaluatedClaims(),
                                    "missingEvidence", result.issues().stream()
                                            .flatMap(issue -> issue.missingEvidence().stream())
                                            .distinct()
                                            .toList()),
                            result),
                    advisorTraceRecorder));
        }

        YamlSkillManifest.LinterManifest linter = definition.linter();
        if (linter != null && "regex".equals(linter.getType()) && linter.getRegex() != null)
        {
            String detail = linter.getRegex().getMessage();
            if (detail == null || detail.isBlank())
            {
                detail = "Return output that matches the configured regex linter.";
            }

            advisors.add(new LinterCallAdvisor(
                    definition.manifest().getName(),
                    linter.getType(),
                    Pattern.compile(linter.getRegex().getPattern()),
                    detail,
                    linter.getMaxRetries(),
                    outcome -> executionStateService.recordLinterOutcome(BifrostSession.getCurrentSession(), outcome),
                    advisorTraceRecorder));
        }

        return List.copyOf(advisors);
    }

    private Map<String, Object> mutationPayload(AdvisorTraceFact fact)
    {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", fact.kind().name().toLowerCase(Locale.ROOT));
        payload.putAll(fact.attributes());
        return Map.copyOf(payload);
    }
}
