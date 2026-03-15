# Ticket: eng-005-chatclient-binding.md
## Issue: Configure Dual Spring AI ChatClient Bindings

### Context
Bifröst requires routing context to either `heavy` or `light` sub-agents based on the developer's properties inside `application.yml`. 

### Requirements
1. **Properties Definition:**
   - Expose `bifrost.models.heavy.model-name` and `bifrost.models.light.model-name` properties.
   - Utilize Spring Boot's `@ConfigurationProperties` to read these values.
2. **Dual Client Bean configuration:**
   - Define a `@Configuration` class `BifrostAIClientConfiguration`.
   - Setup two `ChatClient` Spring Bean qualifiers: `@Qualifier("heavyChatClient")` and `@Qualifier("lightChatClient")`.
   - Use Spring AI's `ChatClient.Builder` to configure these specific instances based on the properties. Provide smart fallbacks if the end-user doesn't explicitly define one (e.g. fallback light to heavy if only one is provided).
3. **Model Tiering Resolver:**
   - Built a simple resolution layer so that when a `@SkillMethod` specifies `modelPreference=LIGHT`, it fetches the `lightChatClient` dynamically from the Spring Context.

### Acceptance Criteria
- Integration test correctly reads `application-test.yml` and binds two valid `ChatClient`s.
- Resolver logic cleanly supplies the appropriate qualitative ChatClient bean based on an enum value.
