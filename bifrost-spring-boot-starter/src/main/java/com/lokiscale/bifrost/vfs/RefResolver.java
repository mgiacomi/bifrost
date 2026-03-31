package com.lokiscale.bifrost.vfs;

import com.lokiscale.bifrost.core.BifrostSession;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface RefResolver {

    Object resolveArgument(Object value, BifrostSession session);

    default Map<String, Object> resolveArguments(Map<String, Object> arguments, BifrostSession session) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        safeArguments.forEach((key, value) -> resolved.put(key, resolveValue(value, session)));
        return Collections.unmodifiableMap(resolved);
    }

    private Object resolveValue(Object value, BifrostSession session) {
        if (value instanceof Map<?, ?> nestedMap) {
            Map<Object, Object> resolvedMap = new LinkedHashMap<>();
            nestedMap.forEach((key, nestedValue) -> resolvedMap.put(key, resolveValue(nestedValue, session)));
            return Collections.unmodifiableMap(resolvedMap);
        }
        if (value instanceof List<?> nestedList) {
            List<Object> resolvedList = new ArrayList<>(nestedList.size());
            nestedList.forEach(item -> resolvedList.add(resolveValue(item, session)));
            return Collections.unmodifiableList(resolvedList);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> resolvedValues = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                resolvedValues.add(resolveValue(Array.get(value, index), session));
            }
            return Collections.unmodifiableList(resolvedValues);
        }
        return resolveArgument(value, session);
    }
}
