package com.lokiscale.bifrost.core;

import com.lokiscale.bifrost.autoconfigure.BifrostAutoConfiguration;
import com.lokiscale.bifrost.runtime.BifrostMissionTimeoutException;
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
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                        "bifrost.session.max-depth=3",
                        "bifrost.session.mission-timeout=5s")
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
    void honorsMissionTimeoutPropertyThroughStarterWiring() {
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
                        "bifrost.skills.locations=classpath:/skills/valid/planning-disabled-skill.yaml",
                        "bifrost.session.max-depth=3",
                        "bifrost.session.mission-timeout=25ms")
                .withUserConfiguration(BlockingIntegrationConfiguration.class)
                .withBean(SessionLocalVirtualFileSystem.class, () -> new SessionLocalVirtualFileSystem(tempDir));

        contextRunner.run(context -> {
            ExecutionCoordinator coordinator = context.getBean(ExecutionCoordinator.class);
            BifrostSession session = new BifrostSession("session-timeout", 3);

            assertThatThrownBy(() -> coordinator.execute("planning.disabled.skill", "hello world", session, null))
                    .isInstanceOf(BifrostMissionTimeoutException.class)
                    .hasMessageContaining("session-timeout")
                    .hasMessageContaining("planning.disabled.skill");
            assertThat(session.getFramesSnapshot()).isEmpty();
        });
    }

    @Test
    void failsRecursiveYamlLoopAtConfiguredMaxDepthThroughStarterWiring() {
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
                        "bifrost.skills.locations=classpath:/skills/valid/recursive-root-skill.yaml,classpath:/skills/valid/recursive-child-skill.yaml",
                        "bifrost.session.max-depth=3",
                        "bifrost.session.mission-timeout=5s")
                .withUserConfiguration(RecursiveIntegrationConfiguration.class)
                .withBean(SessionLocalVirtualFileSystem.class, () -> new SessionLocalVirtualFileSystem(tempDir));

        contextRunner.run(context -> {
            ExecutionCoordinator coordinator = context.getBean(ExecutionCoordinator.class);
            BifrostSession session = new BifrostSession("session-overflow", 3);

            assertThatThrownBy(() -> coordinator.execute("root.recursive.skill", "hello world", session, null))
                    .isInstanceOf(BifrostStackOverflowException.class)
                    .hasMessageContaining("session-overflow")
                    .hasMessageContaining("child.recursive.skill");
            assertThat(session.getFramesSnapshot()).isEmpty();
            assertThat(session.getExecutionPlan()).isPresent();
            assertThat(session.getExecutionPlan().orElseThrow().capabilityName()).isEqualTo("root.recursive.skill");
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
    static class BlockingIntegrationConfiguration {

        @Bean
        BlockingSkillChatClientFactory blockingSkillChatClientFactory() {
            return new BlockingSkillChatClientFactory();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecursiveIntegrationConfiguration {

        @Bean
        RecursiveSkillChatClientFactory recursiveSkillChatClientFactory() {
            return new RecursiveSkillChatClientFactory();
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
        public org.springframework.ai.chat.client.ChatClient create(com.lokiscale.bifrost.skill.YamlSkillDefinition definition) {
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
        public org.springframework.ai.chat.client.ChatClient create(com.lokiscale.bifrost.skill.YamlSkillDefinition definition) {
            return chatClient;
        }
    }

    static final class BlockingSkillChatClientFactory implements SkillChatClientFactory {

        @Override
        public org.springframework.ai.chat.client.ChatClient create(com.lokiscale.bifrost.skill.YamlSkillDefinition definition) {
            return new BlockingIntegrationChatClient();
        }
    }

    static final class RecursiveSkillChatClientFactory implements SkillChatClientFactory {

        @Override
        public org.springframework.ai.chat.client.ChatClient create(com.lokiscale.bifrost.skill.YamlSkillDefinition definition) {
            if ("root.recursive.skill".equals(definition.manifest().getName())) {
                return new FakeCoordinatorChatClient(
                        new ExecutionPlan(
                                "plan-root",
                                "root.recursive.skill",
                                Instant.parse("2026-03-15T12:00:00Z"),
                                List.of(
                                        new PlanTask("task-1", "Use child.recursive.skill", PlanTaskStatus.PENDING,
                                                "child.recursive.skill", "Use child.recursive.skill", List.of(), List.of(), false, null))),
                        "root mission complete",
                        "{\"topic\":\"mars\"}");
            }
            return new FakeCoordinatorChatClient(
                    new ExecutionPlan(
                            "plan-child",
                            "child.recursive.skill",
                            Instant.parse("2026-03-15T12:01:00Z"),
                            List.of(
                                    new PlanTask("task-1", "Use root.recursive.skill", PlanTaskStatus.PENDING,
                                            "root.recursive.skill", "Use root.recursive.skill", List.of(), List.of(), false, null))),
                    "child mission complete",
                    "{\"topic\":\"mars\"}");
        }
    }

    static final class BlockingIntegrationChatClient implements org.springframework.ai.chat.client.ChatClient {

        @Override
        public ChatClientRequestSpec prompt() {
            return new RequestSpec();
        }

        @Override
        public ChatClientRequestSpec prompt(String content) {
            return new RequestSpec();
        }

        @Override
        public ChatClientRequestSpec prompt(org.springframework.ai.chat.prompt.Prompt prompt) {
            return new RequestSpec();
        }

        @Override
        public Builder mutate() {
            throw new UnsupportedOperationException();
        }

        private static final class RequestSpec implements ChatClientRequestSpec {

            @Override
            public Builder mutate() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChatClientRequestSpec advisors(java.util.function.Consumer<AdvisorSpec> consumer) {
                return this;
            }

            @Override
            public ChatClientRequestSpec advisors(org.springframework.ai.chat.client.advisor.api.Advisor... advisors) {
                return this;
            }

            @Override
            public ChatClientRequestSpec advisors(List<org.springframework.ai.chat.client.advisor.api.Advisor> advisors) {
                return this;
            }

            @Override
            public ChatClientRequestSpec messages(org.springframework.ai.chat.messages.Message... messages) {
                return this;
            }

            @Override
            public ChatClientRequestSpec messages(List<org.springframework.ai.chat.messages.Message> messages) {
                return this;
            }

            @Override
            public <T extends org.springframework.ai.chat.prompt.ChatOptions> ChatClientRequestSpec options(T options) {
                return this;
            }

            @Override
            public ChatClientRequestSpec toolNames(String... toolNames) {
                return this;
            }

            @Override
            public ChatClientRequestSpec tools(Object... tools) {
                return this;
            }

            @Override
            public ChatClientRequestSpec toolCallbacks(org.springframework.ai.tool.ToolCallback... toolCallbacks) {
                return this;
            }

            @Override
            public ChatClientRequestSpec toolCallbacks(List<org.springframework.ai.tool.ToolCallback> toolCallbacks) {
                return this;
            }

            @Override
            public ChatClientRequestSpec toolCallbacks(org.springframework.ai.tool.ToolCallbackProvider... providers) {
                return this;
            }

            @Override
            public ChatClientRequestSpec toolContext(java.util.Map<String, Object> toolContext) {
                return this;
            }

            @Override
            public ChatClientRequestSpec system(String text) {
                return this;
            }

            @Override
            public ChatClientRequestSpec system(org.springframework.core.io.Resource resource, java.nio.charset.Charset charset) {
                return this;
            }

            @Override
            public ChatClientRequestSpec system(org.springframework.core.io.Resource resource) {
                return this;
            }

            @Override
            public ChatClientRequestSpec system(java.util.function.Consumer<PromptSystemSpec> consumer) {
                return this;
            }

            @Override
            public ChatClientRequestSpec user(String text) {
                return this;
            }

            @Override
            public ChatClientRequestSpec user(org.springframework.core.io.Resource resource, java.nio.charset.Charset charset) {
                return this;
            }

            @Override
            public ChatClientRequestSpec user(org.springframework.core.io.Resource resource) {
                return this;
            }

            @Override
            public ChatClientRequestSpec user(java.util.function.Consumer<PromptUserSpec> consumer) {
                return this;
            }

            @Override
            public ChatClientRequestSpec templateRenderer(org.springframework.ai.template.TemplateRenderer renderer) {
                return this;
            }

            @Override
            public CallResponseSpec call() {
                return new ResponseSpec();
            }

            @Override
            public StreamResponseSpec stream() {
                throw new UnsupportedOperationException();
            }
        }

        private static final class ResponseSpec implements CallResponseSpec {

            @Override
            public <T> T entity(org.springframework.core.ParameterizedTypeReference<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T entity(org.springframework.ai.converter.StructuredOutputConverter<T> converter) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T entity(Class<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public org.springframework.ai.chat.client.ChatClientResponse chatClientResponse() {
                throw new UnsupportedOperationException();
            }

            @Override
            public org.springframework.ai.chat.model.ChatResponse chatResponse() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String content() {
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("Latch await returned unexpectedly");
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }

            @Override
            public <T> org.springframework.ai.chat.client.ResponseEntity<org.springframework.ai.chat.model.ChatResponse, T> responseEntity(Class<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> org.springframework.ai.chat.client.ResponseEntity<org.springframework.ai.chat.model.ChatResponse, T> responseEntity(
                    org.springframework.core.ParameterizedTypeReference<T> type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> org.springframework.ai.chat.client.ResponseEntity<org.springframework.ai.chat.model.ChatResponse, T> responseEntity(
                    org.springframework.ai.converter.StructuredOutputConverter<T> converter) {
                throw new UnsupportedOperationException();
            }
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
