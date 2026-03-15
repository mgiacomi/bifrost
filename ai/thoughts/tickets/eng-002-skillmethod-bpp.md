# Ticket: eng-002-skillmethod-bpp.md
## Issue: Implement SkillMethod BeanPostProcessor and Spring AI Function Mapping

### Context
Phase 2 requires dynamically routing native Java methods via Spring AI's native tool calling framework. We must map typical Bifröst method signatures into Spring AI's `java.util.function.Function`.

### Requirements
1. **`SkillMethodBeanPostProcessor`:**
   - Implement Spring's `BeanPostProcessor` to intercept bean creation.
   - Scan beans for methods annotated with `@SkillMethod`.
2. **Capability Metadata Extraction & Function Wrapping:**
   - For each matching method, extract its `name`, `description`, parameters, and response type.
   - Build a `java.util.function.Function<InputType, OutputType>` bridging the LLM's payload execution to the bean method via Reflection.
   - We must provide JSON Schema generation or compatible descriptions of the method signature that Spring AI expects for Tool Definitions.
3. **Registration:**
   - Inject the `CapabilityRegistry` into this BeanPostProcessor and call `register` on it for each auto-discovered capability.

### Acceptance Criteria
- Given a test component with a `@SkillMethod` annotated method, the BeanPostProcessor automatically detects it, wraps it, and registers it.
- The wrapped mechanism correctly parses an incoming Map or JSON-node payload into the method's parameters and invokes the bean.
- Unit tests verify scanning lifecycle and reflection-based invocation via the generated function wrapper.
