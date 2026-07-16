package com.lokiscale.bifrost.internal.skillapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lokiscale.bifrost.api.SkillExecutionEvent;
import com.lokiscale.bifrost.api.SkillExecutionView;
import com.lokiscale.bifrost.internal.core.BifrostSession;
import com.lokiscale.bifrost.internal.core.JournalEntry;
import com.lokiscale.bifrost.internal.core.ExecutionJournal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SkillExecutionViewMapper
{
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>()
    {
    };
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>()
    {
    };

    private final ObjectMapper objectMapper;

    SkillExecutionViewMapper(ObjectMapper objectMapper)
    {
        this.objectMapper = objectMapper;
    }

    SkillExecutionView map(BifrostSession session)
    {
        return map(session.getSessionId(), session.getExecutionJournal());
    }

    SkillExecutionView map(String sessionId, ExecutionJournal journal)
    {
        List<SkillExecutionEvent> events = journal.getEntriesSnapshot().stream()
                .map(this::mapEvent)
                .toList();
        return new SkillExecutionView(sessionId, events);
    }

    private SkillExecutionEvent mapEvent(JournalEntry entry)
    {
        return new SkillExecutionEvent(
                entry.timestamp(),
                entry.level().name(),
                entry.type().name(),
                mapDetails(entry.payload()),
                entry.frameId(),
                entry.route());
    }

    private Map<String, Object> mapDetails(JsonNode payload)
    {
        if (payload == null || payload.isNull())
        {
            LinkedHashMap<String, Object> details = new LinkedHashMap<>();
            details.put("value", null);
            return details;
        }
        if (payload.isObject())
        {
            return objectMapper.convertValue(payload, MAP_TYPE);
        }

        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        if (payload.isTextual())
        {
            details.put("message", payload.asText());
        }
        else if (payload.isArray())
        {
            details.put("value", objectMapper.convertValue(payload, LIST_TYPE));
        }
        else
        {
            details.put("value", objectMapper.convertValue(payload, Object.class));
        }
        return details;
    }
}
