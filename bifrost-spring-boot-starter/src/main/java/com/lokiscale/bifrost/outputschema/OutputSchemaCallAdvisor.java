package com.lokiscale.bifrost.outputschema;

import com.lokiscale.bifrost.core.AdvisorTraceContext;
import com.lokiscale.bifrost.core.AdvisorTraceFact;
import com.lokiscale.bifrost.core.AdvisorTraceRecorder;
import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class OutputSchemaCallAdvisor implements CallAdvisor
{
    private static final Logger log = LoggerFactory.getLogger(OutputSchemaCallAdvisor.class);

    public static final String CONTEXT_KEY = "bifrost.output-schema.outcome";
    public static final String PLANNING_CALL_KEY = "bifrost.advisor.planning-call";

    private static final int MAX_ISSUES_IN_HINT = 4;
    private static final int MAX_ISSUES_IN_OUTCOME = 4;

    private final String skillName;
    private final YamlSkillManifest.OutputSchemaManifest schema;
    private final OutputSchemaValidator validator;
    private final OutputSchemaPromptAugmentor promptAugmentor;
    private final int maxRetries;
    private final OutputSchemaOutcomeRecorder outcomeRecorder;
    private final AdvisorTraceRecorder advisorTraceRecorder;

    public OutputSchemaCallAdvisor(String skillName,
            YamlSkillManifest.OutputSchemaManifest schema,
            OutputSchemaValidator validator,
            OutputSchemaPromptAugmentor promptAugmentor,
            int maxRetries,
            OutputSchemaOutcomeRecorder outcomeRecorder)
    {
        this(skillName, schema, validator, promptAugmentor, maxRetries, outcomeRecorder, AdvisorTraceRecorder.noOp());
    }

    public OutputSchemaCallAdvisor(String skillName,
            YamlSkillManifest.OutputSchemaManifest schema,
            OutputSchemaValidator validator,
            OutputSchemaPromptAugmentor promptAugmentor,
            int maxRetries,
            OutputSchemaOutcomeRecorder outcomeRecorder,
            AdvisorTraceRecorder advisorTraceRecorder)
    {
        this.skillName = Objects.requireNonNull(skillName, "skillName must not be null");
        this.schema = Objects.requireNonNull(schema, "schema must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.promptAugmentor = Objects.requireNonNull(promptAugmentor, "promptAugmentor must not be null");

        if (maxRetries < 0)
        {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }

        this.maxRetries = maxRetries;
        this.outcomeRecorder = Objects.requireNonNull(outcomeRecorder, "outcomeRecorder must not be null");
        this.advisorTraceRecorder = Objects.requireNonNull(advisorTraceRecorder, "advisorTraceRecorder must not be null");
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain)
    {
        Objects.requireNonNull(chatClientRequest, "chatClientRequest must not be null");
        Objects.requireNonNull(callAdvisorChain, "callAdvisorChain must not be null");

        if (Boolean.TRUE.equals(chatClientRequest.context().get(PLANNING_CALL_KEY)))
        {
            return callAdvisorChain.nextCall(chatClientRequest);
        }

        ChatClientRequest currentRequest = chatClientRequest.mutate()
                .prompt(promptAugmentor.augment(chatClientRequest.prompt(), schema))
                .build();

        advisorTraceRecorder.record(AdvisorTraceFact.schemaApplied(
                new AdvisorTraceContext(getName(), skillName, 1, "schema-applied")));

        CallAdvisorChain downstreamChain = callAdvisorChain.copy(this);
        int attempt = 1;

        while (true)
        {
            ChatClientResponse response = downstreamChain.nextCall(currentRequest);
            String candidate = extractAssistantText(response);
            OutputSchemaValidationResult result = validator.validate(candidate, schema);

            if (result.valid())
            {
                advisorTraceRecorder.record(AdvisorTraceFact.passed(
                        new AdvisorTraceContext(getName(), skillName, attempt, "passed"),
                        candidate));

                return record(response, outcome(attempt, OutputSchemaOutcomeStatus.PASSED, null, List.of()));
            }

            if (attempt > maxRetries)
            {
                log.warn("Output schema validation exhausted for skill '{}' after attempt {} of {} (failureMode={}): {}",
                        skillName,
                        attempt,
                        maxRetries + 1,
                        result.failureMode(),
                        summarizeIssues(result.issues(), MAX_ISSUES_IN_HINT));

                OutputSchemaOutcome exhaustedOutcome = outcome(
                        attempt,
                        OutputSchemaOutcomeStatus.EXHAUSTED,
                        result.failureMode(),
                        result.issues());

                advisorTraceRecorder.record(AdvisorTraceFact.exhausted(
                        new AdvisorTraceContext(getName(), skillName, attempt, "exhausted"),
                        result.issues()));

                recordOnSession(exhaustedOutcome);

                throw new BifrostOutputSchemaValidationException(
                        skillName,
                        candidate,
                        result.issues(),
                        attempt,
                        maxRetries,
                        result.failureMode());
            }

            log.warn("Output schema validation retry for skill '{}' after attempt {} of {} (failureMode={}): {}",
                    skillName,
                    attempt,
                    maxRetries + 1,
                    result.failureMode(),
                    summarizeIssues(result.issues(), MAX_ISSUES_IN_HINT));

            record(response, outcome(attempt, OutputSchemaOutcomeStatus.RETRYING, result.failureMode(), result.issues()));

            advisorTraceRecorder.record(AdvisorTraceFact.retryRequested(
                    new AdvisorTraceContext(getName(), skillName, attempt, "retrying"),
                    result.issues()));

            currentRequest = currentRequest.mutate()
                    .prompt(appendHint(currentRequest.prompt(), result))
                    .build();

            downstreamChain = callAdvisorChain.copy(this);
            attempt++;
        }
    }

    @Override
    public String getName()
    {
        return "OutputSchemaCallAdvisor[" + skillName + "]";
    }

    @Override
    public int getOrder()
    {
        return DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 90;
    }

    private OutputSchemaOutcome outcome(int attempt,
            OutputSchemaOutcomeStatus status,
            OutputSchemaFailureMode failureMode,
            List<OutputSchemaValidationIssue> issues)
    {
        return new OutputSchemaOutcome(
                skillName,
                failureMode,
                attempt,
                attempt - 1,
                maxRetries,
                status,
                truncateIssues(issues, MAX_ISSUES_IN_OUTCOME));
    }

    private ChatClientResponse record(ChatClientResponse response, OutputSchemaOutcome outcome)
    {
        recordOnSession(outcome);

        return response.mutate()
                .context(CONTEXT_KEY, outcome)
                .build();
    }

    private void recordOnSession(OutputSchemaOutcome outcome)
    {
        try
        {
            outcomeRecorder.record(outcome);
        }
        catch (IllegalStateException ex)
        {
            if (!isManagedSessionBound())
            {
                // Advisor usage outside a managed Bifrost session still exposes outcome via response context.
                return;
            }
            throw ex;
        }
    }

    private boolean isManagedSessionBound()
    {
        try
        {
            BifrostSession.getCurrentSession();
            return true;
        }
        catch (IllegalStateException ignored)
        {
            return false;
        }
    }

    private Prompt appendHint(Prompt prompt, OutputSchemaValidationResult result)
    {
        StringBuilder hint = new StringBuilder("""
                Output schema validation failed for the previous response.
                Do NOT call any tools again. Use the data you already have from previous tool calls.
                Return ONLY corrected raw JSON that satisfies the configured output_schema.
                Do not include any explanation, markdown, or code fences — just the JSON object.
                Issues:
                """);

        result.issues().stream()
                .limit(MAX_ISSUES_IN_HINT)
                .forEach(issue -> hint.append("- ").append(issueMessage(issue)).append('\n'));

        return prompt.augmentSystemMessage(systemMessage -> systemMessage.mutate()
                .text(joinSystemText(systemMessage.getText(), hint.toString().stripTrailing()))
                .build());
    }

    private String issueMessage(OutputSchemaValidationIssue issue)
    {
        if (StringUtils.hasText(issue.canonicalField()))
        {
            return issue.canonicalField() + ": " + issue.message();
        }
        return issue.message();
    }

    private List<OutputSchemaValidationIssue> truncateIssues(List<OutputSchemaValidationIssue> issues, int maxIssues)
    {
        if (issues == null || issues.isEmpty())
        {
            return List.of();
        }

        return issues.stream()
                .limit(maxIssues)
                .toList();
    }

    private String summarizeIssues(List<OutputSchemaValidationIssue> issues, int maxIssues)
    {
        if (issues == null || issues.isEmpty())
        {
            return "no validation issues recorded";
        }

        List<String> summarized = issues.stream()
                .limit(maxIssues)
                .map(this::issueMessage)
                .toList();

        if (issues.size() > maxIssues)
        {
            summarized = new ArrayList<>(summarized);
            summarized.add("+" + (issues.size() - maxIssues) + " more issue(s)");
        }

        return String.join("; ", summarized);
    }

    private String extractAssistantText(ChatClientResponse response)
    {
        if (response == null || response.chatResponse() == null || response.chatResponse().getResult() == null)
        {
            return "";
        }

        AssistantMessage message = response.chatResponse().getResult().getOutput();
        return message == null || message.getText() == null ? "" : message.getText();
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
