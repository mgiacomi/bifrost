package com.lokiscale.bifrost.autoconfigure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict application-owned Bifrost configuration. Named connections define transport and
 * credentials; model aliases select a connection and a request-scoped provider model ID.
 * Values are never inherited from {@code spring.ai.*}.
 */
@Validated
@ConfigurationProperties(prefix = "bifrost", ignoreUnknownFields = false)
public class BifrostProperties implements InitializingBean
{
    private static final Pattern HTTP_TOKEN = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");

    @Valid
    private Session session = new Session();

    @Valid
    private Skills skills = new Skills();

    @Valid
    private Map<String, ConnectionProperties> connections = new LinkedHashMap<>();

    @Valid
    private Map<String, ModelCatalogEntry> models = new LinkedHashMap<>();

    public Session getSession()
    {
        return session;
    }

    public void setSession(Session session)
    {
        this.session = session == null ? new Session() : session;
    }

    public Skills getSkills()
    {
        return skills;
    }

    public void setSkills(Skills skills)
    {
        this.skills = skills == null ? new Skills() : skills;
    }

    /** Concrete endpoint/account definitions, keyed by application-owned connection name. */
    public Map<String, ConnectionProperties> getConnections()
    {
        return connections;
    }

    public void setConnections(Map<String, ConnectionProperties> connections)
    {
        this.connections = connections == null ? new LinkedHashMap<>() : new LinkedHashMap<>(connections);
    }

    /** Framework model aliases referenced by YAML skills. */
    public Map<String, ModelCatalogEntry> getModels()
    {
        return models;
    }

    public void setModels(Map<String, ModelCatalogEntry> models)
    {
        this.models = models == null ? new LinkedHashMap<>() : new LinkedHashMap<>(models);
    }

    @Override
    public void afterPropertiesSet()
    {
        validateConnections();
        validateModels();
    }

    private void validateConnections()
    {
        for (Map.Entry<String, ConnectionProperties> entry : connections.entrySet())
        {
            String name = entry.getKey();
            if (!StringUtils.hasText(name))
            {
                throw invalid("bifrost.connections", "connection names must not be blank");
            }
            ConnectionProperties connection = entry.getValue();
            String path = "bifrost.connections." + name;
            if (connection == null)
            {
                throw invalid(path, "connection definition must not be null");
            }
            AiDriver driver = connection.getDriver();
            if (driver == null)
            {
                throw invalid(path + ".driver", "is required");
            }
            validateApplicableOptions(path, connection, driver);
            validateRequiredFields(path, connection, driver);
            validateHeaders(path, connection, driver);
        }
    }

    private void validateApplicableOptions(String path, ConnectionProperties connection, AiDriver driver)
    {
        if (StringUtils.hasText(connection.getBaseUrl()) && driver == AiDriver.GEMINI)
        {
            throw invalid(path + ".base-url", "is not supported for driver GEMINI");
        }
        if (StringUtils.hasText(connection.getApiKey()) && driver == AiDriver.OLLAMA)
        {
            throw invalid(path + ".api-key", "is not supported for driver OLLAMA");
        }
        if (connection.getOpenai() != null && driver != AiDriver.OPENAI)
        {
            throw invalid(path + ".openai", "is only supported for driver OPENAI");
        }
        if (connection.getAnthropic() != null && driver != AiDriver.ANTHROPIC)
        {
            throw invalid(path + ".anthropic", "is only supported for driver ANTHROPIC");
        }
        if (connection.getGemini() != null && driver != AiDriver.GEMINI)
        {
            throw invalid(path + ".gemini", "is only supported for driver GEMINI");
        }
    }

    private void validateRequiredFields(String path, ConnectionProperties connection, AiDriver driver)
    {
        switch (driver)
        {
            case OPENAI, ANTHROPIC -> requireText(connection.getApiKey(), path + ".api-key", driver);
            case OLLAMA -> requireText(connection.getBaseUrl(), path + ".base-url", driver);
            case GEMINI -> validateGeminiMode(path, connection);
        }
    }

    private void validateGeminiMode(String path, ConnectionProperties connection)
    {
        GeminiOptions gemini = connection.getGemini();
        boolean apiKeyMode = StringUtils.hasText(connection.getApiKey());
        boolean vertexMode = gemini != null && Boolean.TRUE.equals(gemini.getVertexAi());
        if (apiKeyMode == vertexMode)
        {
            throw invalid(path, "driver GEMINI requires exactly one of api-key mode or gemini.vertex-ai=true");
        }
        if (apiKeyMode && gemini != null)
        {
            throw invalid(path + ".gemini", "is only supported when gemini.vertex-ai=true");
        }
        if (vertexMode)
        {
            requireText(gemini.getProjectId(), path + ".gemini.project-id", AiDriver.GEMINI);
            requireText(gemini.getLocation(), path + ".gemini.location", AiDriver.GEMINI);
        }
    }

    private void validateHeaders(String path, ConnectionProperties connection, AiDriver driver)
    {
        if (!connection.getHeaders().isEmpty() && driver != AiDriver.OPENAI)
        {
            throw invalid(path + ".headers", "is only supported for driver OPENAI");
        }
        for (Map.Entry<String, String> header : connection.getHeaders().entrySet())
        {
            if (!StringUtils.hasText(header.getKey()) || !HTTP_TOKEN.matcher(header.getKey()).matches())
            {
                throw invalid(path + ".headers", "contains an invalid HTTP header name");
            }
            if (header.getValue() == null)
            {
                throw invalid(path + ".headers." + header.getKey(), "value must not be null");
            }
        }
    }

    private void validateModels()
    {
        for (Map.Entry<String, ModelCatalogEntry> entry : models.entrySet())
        {
            String name = entry.getKey();
            if (!StringUtils.hasText(name))
            {
                throw invalid("bifrost.models", "model names must not be blank");
            }
            ModelCatalogEntry model = entry.getValue();
            String path = "bifrost.models." + name;
            if (model == null)
            {
                throw invalid(path, "model definition must not be null");
            }
            if (!StringUtils.hasText(model.getConnection()))
            {
                throw invalid(path + ".connection", "is required");
            }
            if (!connections.containsKey(model.getConnection()))
            {
                throw invalid(path + ".connection", "references unknown connection '" + model.getConnection() + "'");
            }
            if (!StringUtils.hasText(model.getProviderModel()))
            {
                throw invalid(path + ".provider-model", "is required");
            }
        }
    }

    private static void requireText(String value, String path, AiDriver driver)
    {
        if (!StringUtils.hasText(value))
        {
            throw invalid(path, "is required for driver " + driver);
        }
    }

    private static IllegalStateException invalid(String path, String detail)
    {
        return new IllegalStateException(path + " " + detail);
    }

    public static class Session
    {
        private static final int DEFAULT_MAX_DEPTH = 32;
        private static final Duration DEFAULT_MISSION_TIMEOUT = Duration.ofSeconds(60);
        private static final DataSize DEFAULT_MAX_ATTACHMENT_SIZE = DataSize.ofMegabytes(20);

        @Min(1)
        private int maxDepth = DEFAULT_MAX_DEPTH;

        @NotNull
        private Duration missionTimeout = DEFAULT_MISSION_TIMEOUT;

        @Valid
        private Quotas quotas = new Quotas();

        @Valid
        private Attachments attachments = new Attachments();

        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
        public Duration getMissionTimeout() { return missionTimeout; }
        public void setMissionTimeout(Duration missionTimeout)
        {
            if (missionTimeout == null || missionTimeout.isZero() || missionTimeout.isNegative())
            {
                throw new IllegalArgumentException("missionTimeout must be greater than zero");
            }
            this.missionTimeout = missionTimeout;
        }
        public Quotas getQuotas() { return quotas; }
        public void setQuotas(Quotas quotas) { this.quotas = quotas == null ? new Quotas() : quotas; }
        public Attachments getAttachments() { return attachments; }
        public void setAttachments(Attachments attachments) { this.attachments = attachments == null ? new Attachments() : attachments; }

        public static class Attachments
        {
            @NotNull
            private DataSize maxSize = DEFAULT_MAX_ATTACHMENT_SIZE;
            public DataSize getMaxSize() { return maxSize; }
            public void setMaxSize(DataSize maxSize)
            {
                if (maxSize == null || maxSize.toBytes() < 0)
                {
                    throw new IllegalArgumentException("attachments.maxSize must be zero or greater");
                }
                this.maxSize = maxSize;
            }
        }

        public static class Quotas
        {
            @Min(0) private int maxSkillInvocations = 64;
            @Min(0) private int maxToolInvocations = 128;
            @Min(0) private int maxLinterRetries = 32;
            @Min(0) private int maxModelCalls = 64;
            @Min(0) private int maxUsageUnits = 200_000;
            public int getMaxSkillInvocations() { return maxSkillInvocations; }
            public void setMaxSkillInvocations(int value) { maxSkillInvocations = value; }
            public int getMaxToolInvocations() { return maxToolInvocations; }
            public void setMaxToolInvocations(int value) { maxToolInvocations = value; }
            public int getMaxLinterRetries() { return maxLinterRetries; }
            public void setMaxLinterRetries(int value) { maxLinterRetries = value; }
            public int getMaxModelCalls() { return maxModelCalls; }
            public void setMaxModelCalls(int value) { maxModelCalls = value; }
            public int getMaxUsageUnits() { return maxUsageUnits; }
            public void setMaxUsageUnits(int value) { maxUsageUnits = value; }
        }
    }

    public static class Skills
    {
        private List<String> locations = List.of("classpath:/skills/**/*.yaml");
        public List<String> getLocations() { return locations; }
        public void setLocations(List<String> locations)
        {
            this.locations = locations == null || locations.isEmpty()
                    ? List.of("classpath:/skills/**/*.yaml") : List.copyOf(locations);
        }
    }

    public static class ConnectionProperties
    {
        @NotNull
        private AiDriver driver;
        private String baseUrl;
        private String apiKey;
        private Map<String, String> headers = new LinkedHashMap<>();
        @Valid private OpenAiOptions openai;
        @Valid private AnthropicOptions anthropic;
        @Valid private GeminiOptions gemini;

        public AiDriver getDriver() { return driver; }
        public void setDriver(AiDriver driver) { this.driver = driver; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers)
        {
            this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        }
        public OpenAiOptions getOpenai() { return openai; }
        public void setOpenai(OpenAiOptions openai) { this.openai = openai; }
        public AnthropicOptions getAnthropic() { return anthropic; }
        public void setAnthropic(AnthropicOptions anthropic) { this.anthropic = anthropic; }
        public GeminiOptions getGemini() { return gemini; }
        public void setGemini(GeminiOptions gemini) { this.gemini = gemini; }

        @Override
        public String toString()
        {
            return "ConnectionProperties[driver=" + driver + ", credentialsConfigured="
                    + StringUtils.hasText(apiKey) + ", headersConfigured=" + !headers.isEmpty() + "]";
        }
    }

    public static class OpenAiOptions
    {
        private String organizationId;
        private String projectId;
        private String chatCompletionsPath;
        public String getOrganizationId() { return organizationId; }
        public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getChatCompletionsPath() { return chatCompletionsPath; }
        public void setChatCompletionsPath(String path) { chatCompletionsPath = path; }
    }

    public static class AnthropicOptions
    {
        private String completionsPath;
        private String version;
        private String betaVersion;
        public String getCompletionsPath() { return completionsPath; }
        public void setCompletionsPath(String value) { completionsPath = value; }
        public String getVersion() { return version; }
        public void setVersion(String value) { version = value; }
        public String getBetaVersion() { return betaVersion; }
        public void setBetaVersion(String value) { betaVersion = value; }
    }

    public static class GeminiOptions
    {
        private Boolean vertexAi;
        private String projectId;
        private String location;
        private String credentialsUri;
        public Boolean getVertexAi() { return vertexAi; }
        public void setVertexAi(Boolean value) { vertexAi = value; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String value) { projectId = value; }
        public String getLocation() { return location; }
        public void setLocation(String value) { location = value; }
        public String getCredentialsUri() { return credentialsUri; }
        public void setCredentialsUri(String value) { credentialsUri = value; }
    }

    public static class ModelCatalogEntry
    {
        @NotBlank private String connection;
        @NotBlank private String providerModel;
        private Set<@NotBlank String> thinkingLevels = new LinkedHashSet<>();
        public String getConnection() { return connection; }
        public void setConnection(String connection) { this.connection = connection; }
        public String getProviderModel() { return providerModel; }
        public void setProviderModel(String providerModel) { this.providerModel = providerModel; }
        public Set<String> getThinkingLevels() { return Set.copyOf(thinkingLevels); }
        public void setThinkingLevels(Set<String> values)
        {
            thinkingLevels = values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
        }
        public boolean supportsThinking() { return !thinkingLevels.isEmpty(); }
        public boolean supportsThinkingLevel(String level)
        {
            return !StringUtils.hasText(level) ? !supportsThinking() : thinkingLevels.contains(level);
        }
    }
}
