package com.lokiscale.bifrost.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.lokiscale.bifrost.annotation.SkillMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.io.Resource;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class SkillMethodBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SkillMethodBeanPostProcessor.class);

    private final CapabilityRegistry capabilityRegistry;
    private final ObjectMapper objectMapper;
    private final BifrostExceptionTransformer bifrostExceptionTransformer;

    public SkillMethodBeanPostProcessor(CapabilityRegistry capabilityRegistry) {
        this(capabilityRegistry, new ObjectMapper(), new DefaultBifrostExceptionTransformer());
    }

    public static SkillMethodBeanPostProcessor create(CapabilityRegistry capabilityRegistry,
                                                      BifrostExceptionTransformer bifrostExceptionTransformer) {
        return new SkillMethodBeanPostProcessor(capabilityRegistry, new ObjectMapper(), bifrostExceptionTransformer);
    }

    SkillMethodBeanPostProcessor(CapabilityRegistry capabilityRegistry,
                                 ObjectMapper objectMapper,
                                 BifrostExceptionTransformer bifrostExceptionTransformer) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.bifrostExceptionTransformer = Objects.requireNonNull(
                bifrostExceptionTransformer,
                "bifrostExceptionTransformer must not be null");
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

        CapabilityInvoker capabilityInvoker = arguments -> invokeSkillMethod(bean, method, capabilityName, arguments);

        CapabilityMetadata metadata = new CapabilityMetadata(
                beanName + "#" + method.getName(),
                capabilityName,
                capabilityDescription,
                annotation.modelPreference(),
                SkillExecutionDescriptor.none(),
                Set.of(),
                capabilityInvoker,
                CapabilityKind.JAVA_METHOD,
                new CapabilityToolDescriptor(capabilityName, capabilityDescription, toolDefinition.inputSchema()),
                null);

        capabilityRegistry.register(capabilityName, metadata);
    }

    private Object invokeSkillMethod(Object bean, Method method, String capabilityName, Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        ArrayList<InputStream> openedStreams = new ArrayList<>();
        try {
            Object[] invocationArguments = bindArguments(method, safeArguments, openedStreams);
            ReflectionUtils.makeAccessible(method);
            Object result = ReflectionUtils.invokeMethod(method, bean, invocationArguments);
            return objectMapper.writeValueAsString(result);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize capability result for " + capabilityName, ex);
        }
        catch (IllegalArgumentException ex) {
            throw ex;
        }
        catch (RuntimeException ex) {
            log.warn("Capability '{}' failed during deterministic execution", capabilityName, ex);
            return bifrostExceptionTransformer.transform(ex);
        }
        finally {
            closeStreams(capabilityName, openedStreams);
        }
    }

    private Object[] bindArguments(Method method, Map<String, Object> arguments, java.util.List<InputStream> openedStreams) {
        Parameter[] parameters = method.getParameters();
        Object[] bound = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            Object rawValue = arguments.get(parameter.getName());
            ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
            if (rawValue == null && toolParam != null && !toolParam.required()) {
                bound[index] = null;
                continue;
            }
            bound[index] = convertArgument(parameter, rawValue, openedStreams);
        }
        return bound;
    }

    private Object convertArgument(Parameter parameter, Object rawValue, java.util.List<InputStream> openedStreams) {
        if (rawValue == null) {
            return null;
        }

        JavaType parameterJavaType = objectMapper.constructType(parameter.getParameterizedType());
        Class<?> parameterType = parameterJavaType.getRawClass();
        if (Resource.class.isAssignableFrom(parameterType)) {
            return convertToResource(parameter, rawValue);
        }
        if (byte[].class.equals(parameterType)) {
            return convertToBytes(parameter, rawValue);
        }
        if (InputStream.class.isAssignableFrom(parameterType)) {
            InputStream stream = convertToInputStream(parameter, rawValue);
            openedStreams.add(stream);
            return stream;
        }
        if (String.class.equals(parameterType) && rawValue instanceof Resource resource) {
            return convertResourceToString(parameter, resource);
        }
        if (parameterType.isInstance(rawValue)
                && !parameterJavaType.isContainerType()
                && isSimpleBindableType(parameterType)) {
            return rawValue;
        }
        Object materializedValue = materializeValue(rawValue, parameterJavaType, openedStreams);
        return objectMapper.convertValue(materializedValue, parameterJavaType);
    }

    private Object materializeValue(Object rawValue, JavaType targetType, java.util.List<InputStream> openedStreams) {
        if (rawValue == null) {
            return null;
        }

        Class<?> rawClass = targetType.getRawClass();
        if (Resource.class.isAssignableFrom(rawClass)) {
            return rawValue;
        }
        if (String.class.equals(rawClass) && rawValue instanceof Resource resource) {
            return convertResourceToString(null, resource);
        }
        if (byte[].class.equals(rawClass) && rawValue instanceof Resource resource) {
            return convertToBytes(null, resource);
        }
        if (InputStream.class.isAssignableFrom(rawClass) && rawValue instanceof Resource resource) {
            InputStream stream = convertToInputStream(null, resource);
            openedStreams.add(stream);
            return stream;
        }
        if (targetType.isMapLikeType() && rawValue instanceof Map<?, ?> mapValue) {
            JavaType valueType = targetType.getContentType() == null
                    ? objectMapper.constructType(Object.class)
                    : targetType.getContentType();
            Map<Object, Object> materialized = new LinkedHashMap<>();
            mapValue.forEach((key, value) -> materialized.put(key, materializeValue(value, valueType, openedStreams)));
            return materialized;
        }
        if (targetType.isCollectionLikeType() && rawValue instanceof List<?> listValue) {
            JavaType contentType = targetType.getContentType() == null
                    ? objectMapper.constructType(Object.class)
                    : targetType.getContentType();
            return listValue.stream()
                    .map(value -> materializeValue(value, contentType, openedStreams))
                    .toList();
        }
        if (rawClass.isArray() && rawValue instanceof List<?> listValue) {
            JavaType contentType = targetType.getContentType() == null
                    ? objectMapper.constructType(Object.class)
                    : targetType.getContentType();
            return listValue.stream()
                    .map(value -> materializeValue(value, contentType, openedStreams))
                    .toList();
        }
        if (rawValue instanceof Map<?, ?> mapValue && !isSimpleBindableType(rawClass)) {
            Map<String, JavaType> propertyTypes = propertyTypes(targetType);
            Map<String, Object> materialized = new LinkedHashMap<>();
            mapValue.forEach((key, value) -> {
                String propertyName = String.valueOf(key);
                JavaType propertyType = propertyTypes.getOrDefault(propertyName, objectMapper.constructType(Object.class));
                materialized.put(propertyName, materializeValue(value, propertyType, openedStreams));
            });
            return materialized;
        }
        return rawValue;
    }

    private Map<String, JavaType> propertyTypes(JavaType targetType) {
        return objectMapper.getDeserializationConfig()
                .introspect(targetType)
                .findProperties()
                .stream()
                .filter(definition -> definition.getPrimaryMember() != null)
                .collect(java.util.stream.Collectors.toMap(
                        BeanPropertyDefinition::getName,
                        definition -> definition.getPrimaryMember().getType(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private boolean isSimpleBindableType(Class<?> rawClass) {
        return rawClass.isPrimitive()
                || Number.class.isAssignableFrom(rawClass)
                || CharSequence.class.isAssignableFrom(rawClass)
                || Boolean.class.equals(rawClass)
                || Enum.class.isAssignableFrom(rawClass)
                || Object.class.equals(rawClass);
    }

    private Resource convertToResource(Parameter parameter, Object rawValue) {
        if (rawValue instanceof Resource resource) {
            return resource;
        }
        throw new IllegalArgumentException("Parameter '" + parameterName(parameter) + "' requires a Resource-backed ref payload");
    }

    private byte[] convertToBytes(Parameter parameter, Object rawValue) {
        if (rawValue instanceof byte[] bytes) {
            return bytes;
        }
        if (rawValue instanceof Resource resource) {
            try {
                return StreamUtils.copyToByteArray(resource.getInputStream());
            }
            catch (IOException ex) {
                throw new IllegalStateException("Failed to read binary payload for parameter '" + parameterName(parameter) + "'", ex);
            }
        }
        return objectMapper.convertValue(rawValue, byte[].class);
    }

    private InputStream convertToInputStream(Parameter parameter, Object rawValue) {
        if (rawValue instanceof InputStream stream) {
            return stream;
        }
        if (rawValue instanceof Resource resource) {
            try {
                return resource.getInputStream();
            }
            catch (IOException ex) {
                throw new IllegalStateException("Failed to open stream for parameter '" + parameterName(parameter) + "'", ex);
            }
        }
        throw new IllegalArgumentException("Parameter '" + parameterName(parameter) + "' requires a Resource-backed ref payload");
    }

    private String convertResourceToString(Parameter parameter, Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to read text payload for parameter '" + parameterName(parameter) + "'", ex);
        }
    }

    private String parameterName(Parameter parameter) {
        return parameter == null ? "<nested>" : parameter.getName();
    }

    private void closeStreams(String capabilityName, java.util.List<InputStream> openedStreams) {
        for (InputStream stream : openedStreams) {
            try {
                stream.close();
            }
            catch (IOException ex) {
                log.warn("Capability '{}' failed while closing opened ref stream", capabilityName, ex);
            }
        }
    }
}
