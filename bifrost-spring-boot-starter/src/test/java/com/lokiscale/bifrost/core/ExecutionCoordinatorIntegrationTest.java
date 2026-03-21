package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.chat.SkillChatClientFactory;
import com.lokiscale.bifrost.skill.EffectiveSkillExecutionConfiguration;
import com.lokiscale.bifrost.annotation.SkillMethod;
import com.lokiscale.bifrost.vfs.SessionLocalVirtualFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionCoordinatorIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void executesCoordinatorFlowEndToEndWithPlanStateVisibleToolsAndStrictRefResolution() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        BifrostAutoConfiguration.class))
                .withInitializer(context -> {
                    try {
                        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                        for (PropertySource<?> propertySource : loader.load("application-test", new ClassPathResource("application-test.yml"))) {
                            context.getEnvironment().getPropertySources().addLast(propertySource);
                        }
                    }
                    catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .withPropertyValues(
                        "bifrost.skills.locations=classpath:/skills/valid/allowed-skills-root.yaml,classpath:/skills/valid/allowed-child-skill.yaml,classpath:/skills/valid/disallowed-child-skill.yaml",
                        "bifrost.session.max-depth=3")
                .withUserConfiguration(IntegrationConfiguration.class)
                .withBean(SessionLocalVirtualFileSystem.class, () -> new SessionLocalVirtualFileSystem(tempDir));

        contextRunner.run(context -> {
            ExecutionCoordinator coordinator = context.getBean(ExecutionCoordinator.class);
            RecordingSkillChatClientFactory factory = context.getBean(RecordingSkillChatClientFactory.class);
            BifrostSession session = new BifrostSession("session-1", 3);

            Path refFile = context.getBean(SessionLocalVirtualFileSystem.class)
                    .sessionRoot(session)
                    .resolve("artifacts/message.txt");
            Files.createDirectories(refFile.getParent());
            Files.writeString(refFile, "hello from ref", StandardCharsets.UTF_8);

            String content = coordinator.execute(
                    "root.visible.skill",
                    "hello world",
                    session,
                    UsernamePasswordAuthenticationToken.authenticated(
                            "user",
                            "pw",
                            AuthorityUtils.createAuthorityList("ROLE_ALLOWED")));

            assertThat(content).isEqualTo("integration complete");
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
            assertThat(factory.chatClient.toolNamesSeen).containsExactly("allowed.visible.skill");
            assertThat(factory.chatClient.toolNamesByCall).containsExactly(List.of(), List.of("allowed.visible.skill"));
            assertThat(factory.chatClient.systemMessagesSeen).hasSize(2);
            assertThat(factory.chatClient.systemMessagesSeen.get(1)).contains("plan-1", "VALID", "Use allowed.visible.skill");
            assertThat(factory.chatClient.lastToolResult).isEqualTo("\"child:hello from ref\"");
            com.fasterxml.jackson.databind.JsonNode loggedArguments = session.getJournalSnapshot().stream()
                    .filter(entry -> entry.type() == JournalEntryType.TOOL_CALL)
                    .findFirst()
                    .orElseThrow()
                    .payload()
                    .get("details")
                    .get("arguments");
            assertThat(loggedArguments.properties())
                    .anySatisfy(property -> assertThat(property.getValue().textValue()).isEqualTo("ref://artifacts/message.txt"));
            assertThat(session.getFramesSnapshot()).isEmpty();
        });
    }

    @Test
    void executesCoordinatorFlowEndToEndWithBinaryRefsIntoByteArrayTools() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        BifrostAutoConfiguration.class))
                .withInitializer(context -> {
                    try {
                        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                        for (PropertySource<?> propertySource : loader.load("application-test", new ClassPathResource("application-test.yml"))) {
                            context.getEnvironment().getPropertySources().addLast(propertySource);
                        }
                    }
                    catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                })
                .withPropertyValues(
                        "bifrost.skills.locations=classpath:/skills/valid/binary-root-skill.yaml,classpath:/skills/valid/binary-child-skill.yaml",
                        "bifrost.session.max-depth=3")
                .withUserConfiguration(BinaryIntegrationConfiguration.class)
                .withBean(SessionLocalVirtualFileSystem.class, () -> new SessionLocalVirtualFileSystem(tempDir));

        contextRunner.run(context -> {
            ExecutionCoordinator coordinator = context.getBean(ExecutionCoordinator.class);
            BinaryRecordingSkillChatClientFactory factory = context.getBean(BinaryRecordingSkillChatClientFactory.class);
            BifrostSession session = new BifrostSession("session-binary", 3);

            Path refFile = context.getBean(SessionLocalVirtualFileSystem.class)
                    .sessionRoot(session)
                    .resolve("artifacts/payload.bin");
            Files.createDirectories(refFile.getParent());
            Files.write(refFile, new byte[]{0x00, (byte) 0xFF, 0x41});

            String content = coordinator.execute(
                    "root.binary.skill",
                    "process binary payload",
                    session,
                    UsernamePasswordAuthenticationToken.authenticated(
                            "user",
                            "pw",
                            AuthorityUtils.createAuthorityList("ROLE_ALLOWED")));

            assertThat(content).isEqualTo("binary integration complete");
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
            assertThat(factory.chatClient.toolNamesSeen).containsExactly("binary.visible.skill");
            assertThat(factory.chatClient.toolNamesByCall).containsExactly(List.of(), List.of("binary.visible.skill"));
            assertThat(factory.chatClient.lastToolResult).isEqualTo("\"binary:00ff41\"");
            com.fasterxml.jackson.databind.JsonNode loggedArguments = session.getJournalSnapshot().stream()
                    .filter(entry -> entry.type() == JournalEntryType.TOOL_CALL)
                    .findFirst()
                    .orElseThrow()
                    .payload()
                    .get("details")
                    .get("arguments");
            assertThat(loggedArguments.properties())
                    .anySatisfy(property -> assertThat(property.getValue().textValue()).isEqualTo("ref://artifacts/payload.bin"));
            assertThat(session.getFramesSnapshot()).isEmpty();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class IntegrationConfiguration {

        @Bean
        RecordingSkillChatClientFactory recordingSkillChatClientFactory() {
            return new RecordingSkillChatClientFactory();
        }

        @Bean
        TargetBean targetBean() {
            return new TargetBean();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BinaryIntegrationConfiguration {

        @Bean
        BinaryRecordingSkillChatClientFactory binaryRecordingSkillChatClientFactory() {
            return new BinaryRecordingSkillChatClientFactory();
        }

        @Bean
        TargetBean targetBean() {
            return new TargetBean();
        }
    }

    static class TargetBean {

        @SkillMethod(name = "deterministicTarget", description = "Deterministic target")
        String deterministicTarget(String payload) {
            return "child:" + payload;
        }

        @SkillMethod(name = "internal.only.target", description = "Internal only target")
        String internalOnlyTarget(String payload) {
            return "internal:" + payload;
        }

        @SkillMethod(name = "binaryTarget", description = "Binary target")
        String binaryTarget(byte[] payload) {
            StringBuilder builder = new StringBuilder();
            for (byte value : payload) {
                builder.append(String.format("%02x", value));
            }
            return "binary:" + builder;
        }
    }

    static final class RecordingSkillChatClientFactory implements SkillChatClientFactory {

        private final FakeCoordinatorChatClient chatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-1",
                        "root.visible.skill",
                        Instant.parse("2026-03-15T12:00:00Z"),
                        List.of(
                                new PlanTask("task-1", "Use allowed.visible.skill", PlanTaskStatus.PENDING,
                                        "allowed.visible.skill", "Use allowed.visible.skill", List.of(), List.of(), false, null),
                                new PlanTask("task-2", "Summarize", PlanTaskStatus.PENDING, null))),
                "integration complete",
                "{\"" + getDeclaredMethod(TargetBean.class, "deterministicTarget", String.class).getParameters()[0].getName()
                        + "\":\"ref://artifacts/message.txt\"}");

        @Override
        public org.springframework.ai.chat.client.ChatClient create(EffectiveSkillExecutionConfiguration executionConfiguration) {
            return chatClient;
        }
    }

    static final class BinaryRecordingSkillChatClientFactory implements SkillChatClientFactory {

        private final FakeCoordinatorChatClient chatClient = new FakeCoordinatorChatClient(
                new ExecutionPlan(
                        "plan-binary",
                        "root.binary.skill",
                        Instant.parse("2026-03-15T12:05:00Z"),
                        List.of(
                                new PlanTask("task-1", "Use binary.visible.skill", PlanTaskStatus.PENDING,
                                        "binary.visible.skill", "Use binary.visible.skill", List.of(), List.of(), false, null),
                                new PlanTask("task-2", "Summarize", PlanTaskStatus.PENDING, null))),
                "binary integration complete",
                "{\"" + getDeclaredMethod(TargetBean.class, "binaryTarget", byte[].class).getParameters()[0].getName()
                        + "\":\"ref://artifacts/payload.bin\"}");

        @Override
        public org.springframework.ai.chat.client.ChatClient create(EffectiveSkillExecutionConfiguration executionConfiguration) {
            return chatClient;
        }
    }

    private static Method getDeclaredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getDeclaredMethod(name, parameterTypes);
        }
        catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Test fixture method lookup failed", ex);
        }
    }
}
