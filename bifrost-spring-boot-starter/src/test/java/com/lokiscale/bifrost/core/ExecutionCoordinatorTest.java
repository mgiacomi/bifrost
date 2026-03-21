package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.autoconfigure.AiProvider;
import com.lokiscale.bifrost.chat.SkillChatClientFactory;
import com.lokiscale.bifrost.runtime.DefaultMissionExecutionEngine;
import com.lokiscale.bifrost.runtime.MissionExecutionEngine;
import com.lokiscale.bifrost.runtime.planning.DefaultPlanningService;
import com.lokiscale.bifrost.runtime.planning.PlanningService;
import com.lokiscale.bifrost.runtime.state.DefaultExecutionStateService;
import com.lokiscale.bifrost.runtime.state.ExecutionStateService;
import com.lokiscale.bifrost.runtime.tool.DefaultToolCallbackFactory;
import com.lokiscale.bifrost.runtime.tool.DefaultToolSurfaceService;
import com.lokiscale.bifrost.runtime.tool.ToolCallbackFactory;
import com.lokiscale.bifrost.runtime.tool.ToolSurfaceService;
import com.lokiscale.bifrost.security.AccessGuard;
import com.lokiscale.bifrost.security.DefaultAccessGuard;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.skill.SkillVisibilityResolver;
import com.lokiscale.bifrost.skill.YamlSkillDefinition;
import com.lokiscale.bifrost.skill.YamlSkillManifest;
import com.lokiscale.bifrost.vfs.RefResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionCoordinatorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void deniesRestrictedRootSkillBeforePlanningOrModelExecution() {
        EffectiveSkillExecutionConfiguration executionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium");
        StubYamlSkillCatalog catalog = new StubYamlSkillCatalog(new YamlSkillDefinition(
                new ByteArrayResource(new byte[0]),
                manifest("root.visible.skill", List.of("allowed.visible.skill")),
                executionConfiguration));

        CapabilityMetadata rootMetadata = new CapabilityMetadata(
                "yaml:root",
                "root.visible.skill",
                "root",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of("ROLE_ROOT"),
                arguments -> "root",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("root.visible.skill", "root"),
                null);
        CapabilityMetadata childMetadata = new CapabilityMetadata(
                "yaml:child",
                "allowed.visible.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "child",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("allowed.visible.skill", "child"),
                "targetBean#deterministicTarget");

        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registry.register(rootMetadata.name(), rootMetadata);
        registry.register(childMetadata.name(), childMetadata);

        FakeCoordinatorChatClient chatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-root-rbac",
                        "root.visible.skill",
                        Instant.parse("2026-03-15T12:00:00Z"),
                        List.of(toolTask("task-1", "Use allowed.visible.skill", "allowed.visible.skill", true))),
                "unused",
                "{\"value\":\"hello\"}");

        ExecutionCoordinator coordinator = coordinator(
                catalog,
                registry,
                (currentSkillName, sessionState, authentication) -> List.of(childMetadata),
                new RecordingSkillChatClientFactory(chatClient),
                (value, session) -> value,
                null,
                true);

        BifrostSession session = new BifrostSession("session-1", 3);

        assertThatThrownBy(() -> coordinator.execute("root.visible.skill", "Say hello", session, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("root.visible.skill");
        assertThat(session.getExecutionPlan()).isEmpty();
        assertThat(session.getJournalSnapshot()).isEmpty();
        assertThat(chatClient.systemMessagesSeen).isEmpty();
        assertThat(session.getFramesSnapshot()).isEmpty();
    }

    @Test
    void clearsStaleSessionAuthenticationBeforeUnauthenticatedRootExecution() {
        EffectiveSkillExecutionConfiguration executionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium");
        StubYamlSkillCatalog catalog = new StubYamlSkillCatalog(new YamlSkillDefinition(
                new ByteArrayResource(new byte[0]),
                manifest("root.visible.skill", List.of("allowed.visible.skill")),
                executionConfiguration));

        CapabilityMetadata rootMetadata = new CapabilityMetadata(
                "yaml:root",
                "root.visible.skill",
                "root",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of("ROLE_ROOT"),
                arguments -> "root",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("root.visible.skill", "root"),
                null);
        CapabilityMetadata childMetadata = new CapabilityMetadata(
                "yaml:child",
                "allowed.visible.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "child",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("allowed.visible.skill", "child"),
                "targetBean#deterministicTarget");

        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registry.register(rootMetadata.name(), rootMetadata);
        registry.register(childMetadata.name(), childMetadata);

        FakeCoordinatorChatClient chatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-root-rbac",
                        "root.visible.skill",
                        Instant.parse("2026-03-15T12:00:00Z"),
                        List.of(toolTask("task-1", "Use allowed.visible.skill", "allowed.visible.skill", true))),
                "unused",
                "{\"value\":\"hello\"}");

        ExecutionCoordinator coordinator = coordinator(
                catalog,
                registry,
                (currentSkillName, sessionState, authentication) -> List.of(childMetadata),
                new RecordingSkillChatClientFactory(chatClient),
                (value, session) -> value,
                null,
                true);

        BifrostSession session = new BifrostSession("session-1", 3);
        session.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "user",
                "pw",
                AuthorityUtils.createAuthorityList("ROLE_ROOT")));

        assertThatThrownBy(() -> coordinator.execute("root.visible.skill", "Say hello", session, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("root.visible.skill");
        assertThat(session.getAuthentication()).isEmpty();
        assertThat(session.getExecutionPlan()).isEmpty();
        assertThat(session.getJournalSnapshot()).isEmpty();
        assertThat(chatClient.systemMessagesSeen).isEmpty();
        assertThat(session.getFramesSnapshot()).isEmpty();
    }

    @Test
    void usesValidatedYamlExecutionConfigAndUpdatesPlanThroughToolInvocation() {
        EffectiveSkillExecutionConfiguration executionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium");
        StubYamlSkillCatalog catalog = new StubYamlSkillCatalog(new YamlSkillDefinition(
                new ByteArrayResource(new byte[0]),
                manifest("root.visible.skill", List.of("allowed.visible.skill")),
                executionConfiguration));

        CapabilityMetadata rootMetadata = new CapabilityMetadata(
                "yaml:root",
                "root.visible.skill",
                "root",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "root",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("root.visible.skill", "root"),
                null);
        CapabilityMetadata childMetadata = new CapabilityMetadata(
                "yaml:child",
                "allowed.visible.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "child:" + arguments.get("value"),
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("allowed.visible.skill", "child"),
                "targetBean#deterministicTarget");

        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registry.register(rootMetadata.name(), rootMetadata);
        registry.register(childMetadata.name(), childMetadata);

        FakeCoordinatorChatClient chatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-1",
                        "root.visible.skill",
                        Instant.parse("2026-03-15T12:00:00Z"),
                        List.of(
                                toolTask("task-1", "Use allowed.visible.skill", "allowed.visible.skill", false),
                                new PlanTask("task-2", "Prepare context", PlanTaskStatus.PENDING, null))),
                "mission complete",
                "{\"value\":\"ref://artifacts/input.txt\"}");
        RecordingSkillChatClientFactory factory = new RecordingSkillChatClientFactory(chatClient);
        SkillVisibilityResolver visibilityResolver = (currentSkillName, sessionState, authentication) -> List.of(childMetadata);
        RefResolver refResolver = (value, session) -> value instanceof String text && text.startsWith("ref://")
                ? "resolved-content"
                : value;
        ExecutionCoordinator coordinator = coordinator(
                catalog,
                registry,
                visibilityResolver,
                factory,
                refResolver,
                null,
                true);

        BifrostSession session = new BifrostSession("session-1", 3);
        String response = coordinator.execute(
                "root.visible.skill",
                "Say hello",
                session,
                UsernamePasswordAuthenticationToken.authenticated(
                        "user",
                        "pw",
                        AuthorityUtils.createAuthorityList("ROLE_ALLOWED")));

        assertThat(response).isEqualTo("mission complete");
        assertThat(factory.lastConfiguration).isEqualTo(executionConfiguration);
        assertThat(session.getExecutionPlan()).isPresent();
        assertThat(session.getExecutionPlan().orElseThrow().tasks()).extracting(PlanTask::status)
                .containsExactly(PlanTaskStatus.COMPLETED, PlanTaskStatus.PENDING);
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .contains(JournalEntryType.PLAN_CREATED, JournalEntryType.PLAN_UPDATED, JournalEntryType.TOOL_CALL, JournalEntryType.TOOL_RESULT);
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .containsSubsequence(
                        JournalEntryType.PLAN_CREATED,
                        JournalEntryType.TOOL_CALL,
                        JournalEntryType.PLAN_UPDATED,
                        JournalEntryType.TOOL_RESULT);
        assertThat(chatClient.toolNamesSeen).containsExactly("allowed.visible.skill");
        assertThat(chatClient.toolNamesByCall).containsExactly(List.of(), List.of("allowed.visible.skill"));
        assertThat(chatClient.systemMessagesSeen).hasSize(2);
        assertThat(chatClient.systemMessagesSeen.get(1)).contains("plan-1", "VALID", "task-1", "Use allowed.visible.skill");
        assertThat(chatClient.lastToolResult).isEqualTo("\"child:resolved-content\"");
        assertThat(session.getJournalSnapshot().stream()
                .filter(entry -> entry.type() == JournalEntryType.TOOL_CALL)
                .findFirst()
                .orElseThrow()
                .payload()
                .get("details")
                .get("arguments"))
                .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("value", "ref://artifacts/input.txt")));
        assertThat(session.getFramesSnapshot()).isEmpty();
    }

    @Test
    void skipsPlanningPromptWhenSkillDisablesPlanningMode() {
        EffectiveSkillExecutionConfiguration executionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium");
        YamlSkillManifest manifest = manifest("root.visible.skill", List.of("allowed.visible.skill"));
        manifest.setPlanningMode(false);
        StubYamlSkillCatalog catalog = new StubYamlSkillCatalog(new YamlSkillDefinition(
                new ByteArrayResource(new byte[0]),
                manifest,
                executionConfiguration));

        CapabilityMetadata rootMetadata = new CapabilityMetadata(
                "yaml:root",
                "root.visible.skill",
                "root",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "root",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("root.visible.skill", "root"),
                null);
        CapabilityMetadata childMetadata = new CapabilityMetadata(
                "yaml:child",
                "allowed.visible.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "child:" + arguments.get("value"),
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("allowed.visible.skill", "child"),
                "targetBean#deterministicTarget");

        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registry.register(rootMetadata.name(), rootMetadata);
        registry.register(childMetadata.name(), childMetadata);

        FakeCoordinatorChatClient chatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "unused-plan",
                        "root.visible.skill",
                        Instant.parse("2026-03-15T12:00:00Z"),
                        List.of(toolTask("task-1", "Use allowed.visible.skill", "allowed.visible.skill", true))),
                "mission complete",
                "{\"value\":\"hello\"}",
                false);
        RecordingSkillChatClientFactory factory = new RecordingSkillChatClientFactory(chatClient);

        ExecutionCoordinator coordinator = coordinator(
                catalog,
                registry,
                (currentSkillName, sessionState, authentication) -> List.of(childMetadata),
                factory,
                (value, session) -> value,
                null,
                true);

        BifrostSession session = new BifrostSession("session-1", 3);
        String response = coordinator.execute("root.visible.skill", "Say hello", session, null);

        assertThat(response).isEqualTo("mission complete");
        assertThat(session.getExecutionPlan()).isEmpty();
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .containsExactly(JournalEntryType.UNPLANNED_TOOL_EXECUTION, JournalEntryType.TOOL_RESULT);
        assertThat(chatClient.systemMessagesSeen).containsExactly("Execute the mission using only the visible YAML tools when needed.");
        assertThat(chatClient.toolNamesByCall).containsExactly(List.of("allowed.visible.skill"));
    }

    @Test
    void clearsStalePlanBeforeStartingPlanningDisabledMission() {
        EffectiveSkillExecutionConfiguration executionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium");
        YamlSkillManifest manifest = manifest("root.visible.skill", List.of("allowed.visible.skill"));
        manifest.setPlanningMode(false);
        StubYamlSkillCatalog catalog = new StubYamlSkillCatalog(new YamlSkillDefinition(
                new ByteArrayResource(new byte[0]),
                manifest,
                executionConfiguration));

        CapabilityMetadata rootMetadata = new CapabilityMetadata(
                "yaml:root",
                "root.visible.skill",
                "root",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "root",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("root.visible.skill", "root"),
                null);
        CapabilityMetadata childMetadata = new CapabilityMetadata(
                "yaml:child",
                "allowed.visible.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "child:" + arguments.get("value"),
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("allowed.visible.skill", "child"),
                "targetBean#deterministicTarget");

        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registry.register(rootMetadata.name(), rootMetadata);
        registry.register(childMetadata.name(), childMetadata);

        FakeCoordinatorChatClient chatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "unused-plan",
                        "root.visible.skill",
                        Instant.parse("2026-03-15T12:00:00Z"),
                        List.of(toolTask("task-1", "Use allowed.visible.skill", "allowed.visible.skill", false))),
                "mission complete",
                "{\"value\":\"hello\"}",
                false);

        ExecutionCoordinator coordinator = coordinator(
                catalog,
                registry,
                (currentSkillName, sessionState, authentication) -> List.of(childMetadata),
                new RecordingSkillChatClientFactory(chatClient),
                (value, session) -> value,
                null,
                true);

        BifrostSession session = new BifrostSession("session-1", 3);
        session.replaceExecutionPlan(new ExecutionPlan(
                "stale-plan",
                "old.skill",
                Instant.parse("2026-03-14T12:00:00Z"),
                List.of(new PlanTask("old-task", "Old work", PlanTaskStatus.IN_PROGRESS, "stale note"))));

        String response = coordinator.execute("root.visible.skill", "Say hello", session, null);

        assertThat(response).isEqualTo("mission complete");
        assertThat(session.getExecutionPlan()).isEmpty();
        assertThat(chatClient.systemMessagesSeen).containsExactly("Execute the mission using only the visible YAML tools when needed.");
        assertThat(chatClient.systemMessagesSeen).noneMatch(message -> message.contains("stale-plan"));
    }

    @Test
    void marksMatchingTaskBlockedAndJournalsErrorWhenToolInvocationFails() {
        EffectiveSkillExecutionConfiguration executionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium");
        StubYamlSkillCatalog catalog = new StubYamlSkillCatalog(new YamlSkillDefinition(
                new ByteArrayResource(new byte[0]),
                manifest("root.visible.skill", List.of("allowed.visible.skill")),
                executionConfiguration));

        CapabilityMetadata rootMetadata = new CapabilityMetadata(
                "yaml:root",
                "root.visible.skill",
                "root",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "root",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("root.visible.skill", "root"),
                null);
        CapabilityMetadata childMetadata = new CapabilityMetadata(
                "yaml:child",
                "allowed.visible.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                args -> {
                    throw new IllegalStateException("tool exploded");
                },
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("allowed.visible.skill", "child"),
                "targetBean#deterministicTarget");

        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registry.register(rootMetadata.name(), rootMetadata);
        registry.register(childMetadata.name(), childMetadata);

        FakeCoordinatorChatClient chatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-2",
                        "root.visible.skill",
                        Instant.parse("2026-03-15T12:00:00Z"),
                        List.of(
                                toolTask("task-1", "Use allowed.visible.skill", "allowed.visible.skill", false),
                                new PlanTask("task-2", "Summarize", PlanTaskStatus.PENDING, null))),
                "unused",
                "{\"value\":\"ref://artifacts/input.txt\"}");
        RecordingSkillChatClientFactory factory = new RecordingSkillChatClientFactory(chatClient);
        SkillVisibilityResolver visibilityResolver = (currentSkillName, sessionState, authentication) -> List.of(childMetadata);
        RefResolver refResolver = (value, session) -> value instanceof String text && text.startsWith("ref://")
                ? "resolved-content"
                : value;
        ExecutionCoordinator coordinator = coordinator(
                catalog,
                registry,
                visibilityResolver,
                factory,
                refResolver,
                null,
                true);

        BifrostSession session = new BifrostSession("session-1", 3);

        assertThatThrownBy(() -> coordinator.execute("root.visible.skill", "Say hello", session, null))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("tool exploded");
        assertThat(session.getExecutionPlan()).isPresent();
        assertThat(session.getExecutionPlan().orElseThrow().tasks()).extracting(PlanTask::status)
                .containsExactly(PlanTaskStatus.BLOCKED, PlanTaskStatus.PENDING);
        assertThat(session.getExecutionPlan().orElseThrow().status()).isEqualTo(PlanStatus.STALE);
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .contains(JournalEntryType.PLAN_CREATED, JournalEntryType.PLAN_UPDATED, JournalEntryType.TOOL_CALL, JournalEntryType.ERROR);
    }

    @Test
    void routesUnmappedYamlSkillsBackThroughCoordinatorAndRestoresParentPlan() {
        EffectiveSkillExecutionConfiguration rootExecutionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium");
        EffectiveSkillExecutionConfiguration childExecutionConfiguration = new EffectiveSkillExecutionConfiguration(
                "claude-sonnet",
                AiProvider.ANTHROPIC,
                "anthropic/claude-sonnet-4",
                "medium");
        StubYamlSkillCatalog catalog = new StubYamlSkillCatalog(
                new YamlSkillDefinition(new ByteArrayResource(new byte[0]), manifest("root.visible.skill", List.of("child.llm.skill")),
                        rootExecutionConfiguration),
                new YamlSkillDefinition(new ByteArrayResource(new byte[0]), manifest("child.llm.skill", List.of()),
                        childExecutionConfiguration));

        CapabilityMetadata rootMetadata = new CapabilityMetadata(
                "yaml:root",
                "root.visible.skill",
                "root",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(rootExecutionConfiguration),
                java.util.Set.of(),
                arguments -> "root",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("root.visible.skill", "root"),
                null);
        CapabilityMetadata childMetadata = new CapabilityMetadata(
                "yaml:child-llm",
                "child.llm.skill",
                "child llm",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(childExecutionConfiguration),
                java.util.Set.of(),
                arguments -> {
                    throw new UnsupportedOperationException("should route through coordinator");
                },
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("child.llm.skill", "child llm"),
                null);

        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registry.register(rootMetadata.name(), rootMetadata);
        registry.register(childMetadata.name(), childMetadata);

        FakeCoordinatorChatClient rootChatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-root",
                        "root.visible.skill",
                        Instant.parse("2026-03-15T12:00:00Z"),
                        List.of(
                                toolTask("task-1", "Use child.llm.skill", "child.llm.skill", false),
                                new PlanTask("task-2", "Summarize", PlanTaskStatus.PENDING, null))),
                "root mission complete",
                "{\"topic\":\"ref://artifacts/topic.txt\"}");
        FakeCoordinatorChatClient childChatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-child",
                        "child.llm.skill",
                        Instant.parse("2026-03-15T12:01:00Z"),
                        List.of(new PlanTask("child-task-1", "Analyze mars topic", PlanTaskStatus.PENDING, null))),
                "child mission complete",
                null);
        MultiClientSkillChatClientFactory factory = new MultiClientSkillChatClientFactory(
                java.util.Map.of(
                        rootExecutionConfiguration.frameworkModel(), rootChatClient,
                        childExecutionConfiguration.frameworkModel(), childChatClient));
        SkillVisibilityResolver visibilityResolver = (currentSkillName, sessionState, authentication) ->
                "root.visible.skill".equals(currentSkillName) ? List.of(childMetadata) : List.of();
        RefResolver refResolver = (value, session) -> value instanceof String text && text.startsWith("ref://")
                ? "resolved-content"
                : value;

        ExecutionCoordinator coordinator = coordinator(
                catalog,
                registry,
                visibilityResolver,
                factory,
                refResolver,
                null,
                true);
        coordinator = coordinator(
                catalog,
                registry,
                visibilityResolver,
                factory,
                refResolver,
                coordinator,
                true);

        BifrostSession session = new BifrostSession("session-1", 4);
        String response = coordinator.execute("root.visible.skill", "Say hello", session, null);

        assertThat(response).isEqualTo("root mission complete");
        assertThat(factory.seenConfigurations).containsExactly(rootExecutionConfiguration, childExecutionConfiguration);
        assertThat(rootChatClient.lastToolResult).isEqualTo("\"child mission complete\"");
        assertThat(session.getExecutionPlan()).isPresent();
        assertThat(session.getExecutionPlan().orElseThrow().planId()).isEqualTo("plan-root");
        assertThat(session.getExecutionPlan().orElseThrow().tasks()).extracting(PlanTask::status)
                .containsExactly(PlanTaskStatus.COMPLETED, PlanTaskStatus.PENDING);
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .containsSubsequence(
                        JournalEntryType.PLAN_CREATED,
                        JournalEntryType.TOOL_CALL,
                        JournalEntryType.PLAN_UPDATED,
                        JournalEntryType.TOOL_RESULT);
        assertThat(childChatClient.userMessagesSeen).hasSize(2);
        assertThat(childChatClient.userMessagesSeen.get(1))
                .contains("ref://")
                .doesNotContain("resolved-content");
        assertThat(session.getFramesSnapshot()).isEmpty();
    }

    @Test
    void deniesRestrictedToolInvocationAtExecutionTimeWhenAuthenticationLacksRole() {
        EffectiveSkillExecutionConfiguration executionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium");
        StubYamlSkillCatalog catalog = new StubYamlSkillCatalog(new YamlSkillDefinition(
                new ByteArrayResource(new byte[0]),
                manifest("root.visible.skill", List.of("allowed.visible.skill")),
                executionConfiguration));

        CapabilityMetadata rootMetadata = new CapabilityMetadata(
                "yaml:root",
                "root.visible.skill",
                "root",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "root",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("root.visible.skill", "root"),
                null);
        CapabilityMetadata childMetadata = new CapabilityMetadata(
                "yaml:child",
                "allowed.visible.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of("ROLE_ALLOWED"),
                arguments -> "child:" + arguments.get("value"),
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("allowed.visible.skill", "child"),
                "targetBean#deterministicTarget");

        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registry.register(rootMetadata.name(), rootMetadata);
        registry.register(childMetadata.name(), childMetadata);

        FakeCoordinatorChatClient chatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-rbac",
                        "root.visible.skill",
                        Instant.parse("2026-03-15T12:00:00Z"),
                        List.of(toolTask("task-1", "Use allowed.visible.skill", "allowed.visible.skill", false))),
                "unused",
                "{\"value\":\"hello\"}");

        ExecutionCoordinator coordinator = coordinator(
                catalog,
                registry,
                (currentSkillName, sessionState, authentication) -> List.of(childMetadata),
                new RecordingSkillChatClientFactory(chatClient),
                (value, session) -> value,
                null,
                true);

        BifrostSession session = new BifrostSession("session-1", 3);

        assertThatThrownBy(() -> coordinator.execute("root.visible.skill", "Say hello", session, null))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("Access denied");
        assertThat(session.getExecutionPlan()).isPresent();
        assertThat(session.getExecutionPlan().orElseThrow().tasks()).extracting(PlanTask::status)
                .containsExactly(PlanTaskStatus.BLOCKED);
        assertThat(session.getExecutionPlan().orElseThrow().status()).isEqualTo(PlanStatus.STALE);
    }

    @Test
    void authorizesProtectedChildYamlSkillFromSessionFallback() {
        EffectiveSkillExecutionConfiguration rootExecutionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium");
        EffectiveSkillExecutionConfiguration childExecutionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5-mini",
                AiProvider.OPENAI,
                "openai/gpt-5-mini",
                "low");
        StubYamlSkillCatalog catalog = new StubYamlSkillCatalog(
                new YamlSkillDefinition(new ByteArrayResource(new byte[0]), manifest("root.visible.skill", List.of("child.llm.skill")),
                        rootExecutionConfiguration),
                new YamlSkillDefinition(new ByteArrayResource(new byte[0]), manifest("child.llm.skill", List.of()),
                        childExecutionConfiguration));

        CapabilityMetadata rootMetadata = new CapabilityMetadata(
                "yaml:root",
                "root.visible.skill",
                "root",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(rootExecutionConfiguration),
                java.util.Set.of(),
                arguments -> "root",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("root.visible.skill", "root"),
                null);
        CapabilityMetadata childMetadata = new CapabilityMetadata(
                "yaml:child-llm",
                "child.llm.skill",
                "child llm",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(childExecutionConfiguration),
                java.util.Set.of("ROLE_ALLOWED"),
                arguments -> {
                    throw new UnsupportedOperationException("should route through coordinator");
                },
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("child.llm.skill", "child llm"),
                null);

        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registry.register(rootMetadata.name(), rootMetadata);
        registry.register(childMetadata.name(), childMetadata);

        FakeCoordinatorChatClient rootChatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-root",
                        "root.visible.skill",
                        Instant.parse("2026-03-15T12:00:00Z"),
                        List.of(toolTask("task-1", "Use child.llm.skill", "child.llm.skill", false))),
                "root mission complete",
                "{\"topic\":\"mars\"}");
        FakeCoordinatorChatClient childChatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-child",
                        "child.llm.skill",
                        Instant.parse("2026-03-15T12:01:00Z"),
                        List.of(new PlanTask("child-task-1", "Analyze mars topic", PlanTaskStatus.PENDING, null))),
                "child mission complete",
                null);
        MultiClientSkillChatClientFactory factory = new MultiClientSkillChatClientFactory(
                java.util.Map.of(
                        rootExecutionConfiguration.frameworkModel(), rootChatClient,
                        childExecutionConfiguration.frameworkModel(), childChatClient));
        SkillVisibilityResolver visibilityResolver = (currentSkillName, sessionState, authentication) ->
                "root.visible.skill".equals(currentSkillName) ? List.of(childMetadata) : List.of();

        ExecutionCoordinator coordinator = coordinator(
                catalog,
                registry,
                visibilityResolver,
                factory,
                (value, session) -> value,
                null,
                true,
                true);
        coordinator = coordinator(
                catalog,
                registry,
                visibilityResolver,
                factory,
                (value, session) -> value,
                coordinator,
                true,
                true);

        BifrostSession session = new BifrostSession("session-1", 4);
        String response = coordinator.execute(
                "root.visible.skill",
                "Say hello",
                session,
                UsernamePasswordAuthenticationToken.authenticated(
                        "user",
                        "pw",
                        AuthorityUtils.createAuthorityList("ROLE_ALLOWED")));

        assertThat(response).isEqualTo("root mission complete");
        assertThat(rootChatClient.lastToolResult).isEqualTo("\"child mission complete\"");
        assertThat(session.getExecutionPlan()).isPresent();
        assertThat(session.getExecutionPlan().orElseThrow().tasks()).extracting(PlanTask::status)
                .containsExactly(PlanTaskStatus.COMPLETED);
        assertThat(session.getAuthentication()).isPresent();
    }

    @Test
    void usesMappedSkillToolSchemaInsteadOfGenericMapSchema() {
        EffectiveSkillExecutionConfiguration executionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium");
        String methodSchema = "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}";
        CapabilityMetadata childMetadata = new CapabilityMetadata(
                "yaml:child",
                "allowed.visible.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "child:" + arguments.get("value"),
                CapabilityKind.YAML_SKILL,
                new CapabilityToolDescriptor("allowed.visible.skill", "child", methodSchema),
                "targetBean#deterministicTarget");

        ExecutionStateService stateService = fixedStateService();
        PlanningService planningService = fixedPlanningService(stateService);
        ToolCallback callback = toolCallbackFactory((value, session) -> value, null, stateService, planningService)
                .createToolCallbacks(new BifrostSession("session-1", 2), List.of(childMetadata), null)
                .getFirst();

        assertThat(callback.getToolDefinition().inputSchema()).isEqualTo(methodSchema);
    }

    @Test
    void journalsUnplannedExecutionWithoutMutatingAmbiguousTasks() {
        EffectiveSkillExecutionConfiguration executionConfiguration = new EffectiveSkillExecutionConfiguration(
                "gpt-5",
                AiProvider.OPENAI,
                "openai/gpt-5",
                "medium");
        StubYamlSkillCatalog catalog = new StubYamlSkillCatalog(new YamlSkillDefinition(
                new ByteArrayResource(new byte[0]),
                manifest("root.visible.skill", List.of("allowed.visible.skill")),
                executionConfiguration));

        CapabilityMetadata rootMetadata = new CapabilityMetadata(
                "yaml:root",
                "root.visible.skill",
                "root",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "root",
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("root.visible.skill", "root"),
                null);
        CapabilityMetadata childMetadata = new CapabilityMetadata(
                "yaml:child",
                "allowed.visible.skill",
                "child",
                ModelPreference.LIGHT,
                SkillExecutionDescriptor.from(executionConfiguration),
                java.util.Set.of(),
                arguments -> "child:" + arguments.get("value"),
                CapabilityKind.YAML_SKILL,
                CapabilityToolDescriptor.generic("allowed.visible.skill", "child"),
                "targetBean#deterministicTarget");

        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        registry.register(rootMetadata.name(), rootMetadata);
        registry.register(childMetadata.name(), childMetadata);

        FakeCoordinatorChatClient chatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-3",
                        "root.visible.skill",
                        Instant.parse("2026-03-15T12:00:00Z"),
                        List.of(
                                toolTask("task-1", "Use allowed.visible.skill for source A", "allowed.visible.skill", false),
                                toolTask("task-2", "Use allowed.visible.skill for source B", "allowed.visible.skill", false))),
                "mission complete",
                "{\"value\":\"hello\"}");

        ExecutionCoordinator coordinator = coordinator(
                catalog,
                registry,
                (currentSkillName, sessionState, authentication) -> List.of(childMetadata),
                new RecordingSkillChatClientFactory(chatClient),
                (value, session) -> value,
                null,
                true);

        BifrostSession session = new BifrostSession("session-1", 3);
        String response = coordinator.execute("root.visible.skill", "Say hello", session, null);

        assertThat(response).isEqualTo("mission complete");
        assertThat(session.getExecutionPlan()).isPresent();
        assertThat(session.getExecutionPlan().orElseThrow().tasks()).extracting(PlanTask::status)
                .containsExactly(PlanTaskStatus.PENDING, PlanTaskStatus.PENDING);
        assertThat(session.getJournalSnapshot()).extracting(JournalEntry::type)
                .containsExactly(
                        JournalEntryType.PLAN_CREATED,
                        JournalEntryType.UNPLANNED_TOOL_EXECUTION,
                        JournalEntryType.TOOL_RESULT);
    }

    private static YamlSkillManifest manifest(String name, List<String> allowedSkills) {
        YamlSkillManifest manifest = new YamlSkillManifest();
        manifest.setName(name);
        manifest.setDescription(name);
        manifest.setModel("gpt-5");
        manifest.setAllowedSkills(allowedSkills);
        return manifest;
    }

    private static ExecutionCoordinator coordinator(StubYamlSkillCatalog catalog,
                                                   InMemoryCapabilityRegistry registry,
                                                   SkillVisibilityResolver visibilityResolver,
                                                   SkillChatClientFactory factory,
                                                   RefResolver refResolver,
                                                   ExecutionCoordinator routedCoordinator,
                                                   boolean planningModeEnabled) {
        return coordinator(catalog, registry, visibilityResolver, factory, refResolver, routedCoordinator, planningModeEnabled, null);
    }

    private static ExecutionCoordinator coordinator(StubYamlSkillCatalog catalog,
                                                    InMemoryCapabilityRegistry registry,
                                                    SkillVisibilityResolver visibilityResolver,
                                                    SkillChatClientFactory factory,
                                                    RefResolver refResolver,
                                                    ExecutionCoordinator routedCoordinator,
                                                    boolean planningModeEnabled,
                                                    @Nullable Boolean dropInvocationAuthenticationForCallbacks) {
        ExecutionStateService stateService = fixedStateService();
        PlanningService planningService = fixedPlanningService(stateService);
        ToolSurfaceService toolSurfaceService = new DefaultToolSurfaceService(visibilityResolver);
        ToolCallbackFactory toolCallbackFactory = toolCallbackFactory(
                refResolver,
                routedCoordinator,
                stateService,
                planningService,
                Boolean.TRUE.equals(dropInvocationAuthenticationForCallbacks));
        MissionExecutionEngine missionExecutionEngine = missionExecutionEngine(planningService, stateService);
        AccessGuard accessGuard = new DefaultAccessGuard();
        return new ExecutionCoordinator(
                catalog,
                registry,
                factory,
                toolSurfaceService,
                toolCallbackFactory,
                missionExecutionEngine,
                stateService,
                accessGuard,
                planningModeEnabled);
    }

    private static ToolCallbackFactory toolCallbackFactory(RefResolver refResolver,
                                                           ExecutionCoordinator coordinator,
                                                           ExecutionStateService stateService,
                                                           PlanningService planningService) {
        return toolCallbackFactory(refResolver, coordinator, stateService, planningService, false);
    }

    private static ToolCallbackFactory toolCallbackFactory(RefResolver refResolver,
                                                           ExecutionCoordinator coordinator,
                                                           ExecutionStateService stateService,
                                                           PlanningService planningService,
                                                           boolean dropInvocationAuthenticationForCallbacks) {
        StaticListableBeanFactory beanFactory = coordinator == null
                ? new StaticListableBeanFactory()
                : new StaticListableBeanFactory(java.util.Map.of("executionCoordinator", coordinator));
        DefaultToolCallbackFactory delegate = new DefaultToolCallbackFactory(
                new CapabilityExecutionRouter(
                        refResolver,
                        beanFactory.getBeanProvider(ExecutionCoordinator.class),
                        stateService,
                        new DefaultAccessGuard()),
                planningService,
                stateService);
        if (!dropInvocationAuthenticationForCallbacks) {
            return delegate;
        }
        return (session, capabilities, authentication) ->
                delegate.createToolCallbacks(session, capabilities, null);
    }

    private static ExecutionStateService fixedStateService() {
        return new DefaultExecutionStateService(FIXED_CLOCK);
    }

    private static PlanningService fixedPlanningService(ExecutionStateService stateService) {
        return new DefaultPlanningService(new DefaultPlanTaskLinker(), stateService);
    }

    private static MissionExecutionEngine missionExecutionEngine(PlanningService planningService,
                                                                 ExecutionStateService stateService) {
        return new DefaultMissionExecutionEngine(planningService, stateService);
    }

    private static PlanTask toolTask(String taskId, String title, String capabilityName, boolean autoCompletable) {
        return new PlanTask(
                taskId,
                title,
                PlanTaskStatus.PENDING,
                capabilityName,
                title,
                List.of(),
                List.of(),
                autoCompletable,
                null);
    }

    private static final class StubYamlSkillCatalog extends com.lokiscale.bifrost.skill.YamlSkillCatalog {

        private final java.util.Map<String, YamlSkillDefinition> definitions;

        StubYamlSkillCatalog(YamlSkillDefinition... definitions) {
            super(new com.lokiscale.bifrost.autoconfigure.BifrostModelsProperties(),
                    new com.lokiscale.bifrost.autoconfigure.BifrostSkillProperties());
            this.definitions = java.util.Arrays.stream(definitions)
                    .collect(java.util.stream.Collectors.toMap(definition -> definition.manifest().getName(), definition -> definition));
        }

        @Override
        public YamlSkillDefinition getSkill(String name) {
            return definitions.get(name);
        }
    }

    private static final class RecordingSkillChatClientFactory implements SkillChatClientFactory {

        private final FakeCoordinatorChatClient chatClient;
        private EffectiveSkillExecutionConfiguration lastConfiguration;

        private RecordingSkillChatClientFactory(FakeCoordinatorChatClient chatClient) {
            this.chatClient = chatClient;
        }

        @Override
        public org.springframework.ai.chat.client.ChatClient create(EffectiveSkillExecutionConfiguration executionConfiguration) {
            this.lastConfiguration = executionConfiguration;
            return chatClient;
        }
    }

    private static final class MultiClientSkillChatClientFactory implements SkillChatClientFactory {

        private final java.util.Map<String, FakeCoordinatorChatClient> clientsByModel;
        private final java.util.List<EffectiveSkillExecutionConfiguration> seenConfigurations = new java.util.ArrayList<>();

        private MultiClientSkillChatClientFactory(java.util.Map<String, FakeCoordinatorChatClient> clientsByModel) {
            this.clientsByModel = clientsByModel;
        }

        @Override
        public org.springframework.ai.chat.client.ChatClient create(EffectiveSkillExecutionConfiguration executionConfiguration) {
            seenConfigurations.add(executionConfiguration);
            FakeCoordinatorChatClient chatClient = clientsByModel.get(executionConfiguration.frameworkModel());
            if (chatClient == null) {
                throw new IllegalStateException("No chat client configured for " + executionConfiguration.frameworkModel());
            }
            return chatClient;
        }
    }
}
