package com.lokiscale.bifrost.outputschema;

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

import java.util.List;
import java.util.Objects;

public final class OutputSchemaCallAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(OutputSchemaCallAdvisor.class);

    public static final String CONTEXT_KEY = "bifrost.output-schema.outcome";

    private static final int MAX_ISSUES_IN_HINT = 4;
    private static final int MAX_ISSUES_IN_OUTCOME = 4;

    private final String skillName;
    private final YamlSkillManifest.OutputSchemaManifest schema;
    private final OutputSchemaValidator validator;
    private final OutputSchemaPromptAugmentor promptAugmentor;
    private final int maxRetries;
    private final OutputSchemaOutcomeRecorder outcomeRecorder;

    public OutputSchemaCallAdvisor(String skillName,
                                   YamlSkillManifest.OutputSchemaManifest schema,
                                   OutputSchemaValidator validator,
                                   OutputSchemaPromptAugmentor promptAugmentor,
                                   int maxRetries,
                                   OutputSchemaOutcomeRecorder outcomeRecorder) {
        this.skillName = Objects.requireNonNull(skillName, "skillName must not be null");
        this.schema = Objects.requireNonNull(schema, "schema must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.promptAugmentor = Objects.requireNonNull(promptAugmentor, "promptAugmentor must not be null");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        this.maxRetries = maxRetries;
        this.outcomeRecorder = Objects.requireNonNull(outcomeRecorder, "outcomeRecorder must not be null");
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        Objects.requireNonNull(chatClientRequest, "chatClientRequest must not be null");
        Objects.requireNonNull(callAdvisorChain, "callAdvisorChain must not be null");

        ChatClientRequest currentRequest = chatClientRequest.mutate()
                .prompt(promptAugmentor.augment(chatClientRequest.prompt(), schema))
                .build();
        int attempt = 1;
        while (true) {
            ChatClientResponse response = callAdvisorChain.nextCall(currentRequest);
            String candidate = extractAssistantText(response);
            OutputSchemaValidationResult result = validator.validate(candidate, schema);
            if (result.valid()) {
                return record(response, outcome(attempt, OutputSchemaOutcomeStatus.PASSED, null, List.of()));
            }

            if (attempt > maxRetries) {
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
            currentRequest = currentRequest.mutate()
                    .prompt(appendHint(currentRequest.prompt(), result))
                    .build();
            attempt++;
        }
    }

    @Override
    public String getName() {
        return "OutputSchemaCallAdvisor[" + skillName + "]";
    }

    @Override
    public int getOrder() {
        return DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 90;
    }

    private OutputSchemaOutcome outcome(int attempt,
                                        OutputSchemaOutcomeStatus status,
                                        OutputSchemaFailureMode failureMode,
                                        List<OutputSchemaValidationIssue> issues) {
        return new OutputSchemaOutcome(
                skillName,
                failureMode,
                attempt,
                attempt - 1,
                maxRetries,
                status,
                truncateIssues(issues, MAX_ISSUES_IN_OUTCOME));
    }

    private ChatClientResponse record(ChatClientResponse response, OutputSchemaOutcome outcome) {
        recordOnSession(outcome);
        return response.mutate()
                .context(CONTEXT_KEY, outcome)
                .build();
    }

    private void recordOnSession(OutputSchemaOutcome outcome) {
        try {
            outcomeRecorder.record(outcome);
        }
        catch (IllegalStateException ignored) {
            // Advisor usage outside a managed Bifrost session still exposes outcome via response context.
        }
    }

    private Prompt appendHint(Prompt prompt, OutputSchemaValidationResult result) {
        StringBuilder hint = new StringBuilder("""
                Output schema validation failed for the previous response.
                Return JSON only and satisfy the configured output_schema.
                Issues:
                """);
        result.issues().stream()
                .limit(MAX_ISSUES_IN_HINT)
                .forEach(issue -> hint.append("- ").append(issueMessage(issue)).append('\n'));
        return prompt.augmentSystemMessage(systemMessage -> systemMessage.mutate()
                .text(joinSystemText(systemMessage.getText(), hint.toString().stripTrailing()))
                .build());
    }

    private String issueMessage(OutputSchemaValidationIssue issue) {
        if (StringUtils.hasText(issue.canonicalField())) {
            return issue.canonicalField() + ": " + issue.message();
        }
        return issue.message();
    }

    private List<OutputSchemaValidationIssue> truncateIssues(List<OutputSchemaValidationIssue> issues, int maxIssues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        return issues.stream()
                .limit(maxIssues)
                .toList();
    }

    private String summarizeIssues(List<OutputSchemaValidationIssue> issues, int maxIssues) {
        if (issues == null || issues.isEmpty()) {
            return "no validation issues recorded";
        }
        List<String> summarized = issues.stream()
                .limit(maxIssues)
                .map(this::issueMessage)
                .toList();
        if (issues.size() > maxIssues) {
            summarized = new java.util.ArrayList<>(summarized);
            summarized.add("+" + (issues.size() - maxIssues) + " more issue(s)");
        }
        return String.join("; ", summarized);
    }

    private String extractAssistantText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null || response.chatResponse().getResult() == null) {
            return "";
        }
        AssistantMessage message = response.chatResponse().getResult().getOutput();
        return message == null || message.getText() == null ? "" : message.getText();
    }

    private String joinSystemText(String original, String hint) {
        if (!StringUtils.hasText(original)) {
            return hint;
        }
        return original + "\n\n" + hint;
    }
}
