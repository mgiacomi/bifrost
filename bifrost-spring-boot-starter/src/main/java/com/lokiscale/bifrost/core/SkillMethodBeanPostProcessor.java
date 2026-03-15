package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.annotation.SkillMethod;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

public class SkillMethodBeanPostProcessor implements BeanPostProcessor {

    private final CapabilityRegistry capabilityRegistry;
    private final ObjectMapper objectMapper;

    public SkillMethodBeanPostProcessor(CapabilityRegistry capabilityRegistry) {
        this(capabilityRegistry, new ObjectMapper());
    }

    SkillMethodBeanPostProcessor(CapabilityRegistry capabilityRegistry, ObjectMapper objectMapper) {
        this.capabilityRegistry = capabilityRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        ReflectionUtils.doWithMethods(bean.getClass(), method -> registerSkillMethod(bean, beanName, method),
                method -> method.isAnnotationPresent(SkillMethod.class));
        return bean;
    }

    private void registerSkillMethod(Object bean, String beanName, Method method) {
        SkillMethod annotation = method.getAnnotation(SkillMethod.class);
        String capabilityName = annotation.name().isBlank() ? method.getName() : annotation.name();
        String capabilityDescription = annotation.description().isBlank() ? method.getName() : annotation.description();

        ToolDefinition toolDefinition = ToolDefinition.builder()
                .name(capabilityName)
                .description(capabilityDescription)
                .inputSchema(JsonSchemaGenerator.generateForMethodInput(method))
                .build();

        MethodToolCallback toolCallback = MethodToolCallback.builder()
                .toolDefinition(toolDefinition)
                .toolMethod(method)
                .toolObject(bean)
                .build();

        CapabilityInvoker capabilityInvoker = arguments -> invokeToolCallback(toolCallback, capabilityName, arguments);

        CapabilityMetadata metadata = new CapabilityMetadata(
                beanName + "#" + method.getName(),
                capabilityName,
                capabilityDescription,
                annotation.modelPreference(),
                Set.of(),
                capabilityInvoker);

        capabilityRegistry.register(capabilityName, metadata);
    }

    private Object invokeToolCallback(MethodToolCallback toolCallback, String capabilityName, Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        try {
            String requestPayload = objectMapper.writeValueAsString(safeArguments);
            return toolCallback.call(requestPayload);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize capability arguments for " + capabilityName, ex);
        }
        catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to invoke capability " + capabilityName, ex);
        }
    }
}
