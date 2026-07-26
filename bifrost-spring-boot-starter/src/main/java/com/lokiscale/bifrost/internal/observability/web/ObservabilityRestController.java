package com.lokiscale.bifrost.internal.observability.web;

import com.lokiscale.bifrost.internal.observability.BifrostReleaseVersion;
import com.lokiscale.bifrost.internal.observability.ObservabilityActivationCoordinator;
import com.lokiscale.bifrost.internal.observability.ObservabilityRuntime;
import com.lokiscale.bifrost.internal.observability.web.dto.ObservabilityDtos;
import com.lokiscale.bifrost.internal.runtime.observation.ActiveExecutionSnapshot;
import com.lokiscale.bifrost.internal.runtime.observation.catalog.FinalizedTraceCatalogEntry;
import com.lokiscale.bifrost.internal.runtime.observation.catalog.RegisteredSkillFile;
import com.lokiscale.bifrost.internal.runtime.observation.catalog.TraceCatalogSlice;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@ResponseBody
public final class ObservabilityRestController
{
    private final ObservabilityActivationCoordinator activation;
    private final ObservabilityAccessService access;
    private final ObservabilityDtoMapper mapper;
    private final ObservabilityCursorCodec cursors;
    private final BoundedJsonPageWriter pages;
    private final String releaseVersion;

    public ObservabilityRestController(
            ObservabilityActivationCoordinator activation,
            ObservabilityAccessService access,
            ObservabilityDtoMapper mapper,
            ObservabilityCursorCodec cursors,
            BoundedJsonPageWriter pages)
    {
        this.activation = activation;
        this.access = access;
        this.mapper = mapper;
        this.cursors = cursors;
        this.pages = pages;
        this.releaseVersion = BifrostReleaseVersion.load();
    }

    public ResponseEntity<byte[]> instance(HttpServletRequest request)
    {
        require(ObservabilityAccessService.Operation.INSTANCE_READ);
        validateNoQuery(request);
        ObservabilityRuntime runtime = runtime();
        Instant observedAt = Instant.now(runtime.clock());
        return json(pages.writeObject(new ObservabilityDtos.InstanceStatus(
                runtime.instanceId().toString(), releaseVersion, observedAt, runtime.liveMonitoring().isAvailable(),
                runtime.skills().registeredSkillCount(), runtime.activeExecutions().activeCount(),
                runtime.traces().catalogedTraceCount(), runtime.tracePersistencePolicy(),
                runtime.configuration().getCompletionGraceTtl(),
                runtime.configuration().getTraceCatalogMetadataTtl())));
    }

    public ResponseEntity<byte[]> skills(HttpServletRequest request)
    {
        require(ObservabilityAccessService.Operation.SKILL_READ);
        validateQuery(request);
        ObservabilityRuntime runtime = runtime();
        int pageSize = pages.pageSize(single(request, "pageSize"));
        String encoded = single(request, "cursor");
        ObservabilityCursorCodec.Cursor cursor = encoded == null
                ? ObservabilityCursorCodec.Cursor.initial(runtime.instanceId(), "skills", 0)
                : cursors.decode(encoded, runtime.instanceId(), "skills");
        if (encoded != null && (cursor.afterName() == null
                || runtime.skills().find(cursor.afterName()).isEmpty()))
        {
            throw invalidCursor();
        }
        List<RegisteredSkillFile.Summary> source = runtime.skills().listAfter(cursor.afterName(), pageSize + 1);
        List<ObservabilityDtos.SkillSummary> items = source.stream().map(mapper::skill).toList();
        Instant observedAt = Instant.now(runtime.clock());
        byte[] body = pages.write(items, pageSize, emitted ->
        {
            boolean more = source.size() > emitted.size();
            String next = more && !emitted.isEmpty()
                    ? cursors.encode(cursor.after(emitted.getLast().registeredName())) : null;
            return new ObservabilityDtos.Page<>(emitted, more, next, observedAt);
        });
        return json(body);
    }

    public ResponseEntity<byte[]> skill(@PathVariable String registeredName, HttpServletRequest request)
    {
        require(ObservabilityAccessService.Operation.SKILL_READ);
        validateNoQuery(request);
        return json(pages.writeObject(mapper.skill(
                runtime().skills().find(registeredName).orElseThrow(ObservabilityRestController::notFound))));
    }

    public ResponseEntity<byte[]> active(HttpServletRequest request)
    {
        require(ObservabilityAccessService.Operation.ACTIVE_READ);
        validateQuery(request);
        ObservabilityRuntime runtime = runtime();
        requireLive(runtime);
        int pageSize = pages.pageSize(single(request, "pageSize"));
        String encoded = single(request, "cursor");
        boolean initial = encoded == null;
        long highWater = runtime.activeExecutions().highestOrdinal();
        ObservabilityCursorCodec.Cursor cursor = initial
                ? ObservabilityCursorCodec.Cursor.initial(runtime.instanceId(), "active-executions", highWater)
                : cursors.decode(encoded, runtime.instanceId(), "active-executions");
        if (!initial && (cursor.highWater() == 0 || cursor.beforeOrdinal() == 0
                || cursor.highWater() > highWater))
        {
            throw invalidCursor();
        }
        List<ActiveExecutionSnapshot> source = runtime.activeExecutions()
                .newestFirst(cursor.highWater(), cursor.beforeOrdinal(), pageSize + 1);
        Instant observedAt = Instant.now(runtime.clock());
        List<ObservabilityDtos.ActiveExecution> items = source.stream()
                .map(item -> mapper.active(item, observedAt, runtime.quotas())).toList();
        String resume = initial ? Long.toString(runtime.replayBuffer().currentCursor()) : null;
        byte[] body = pages.write(items, pageSize, emitted ->
        {
            boolean more = source.size() > emitted.size();
            String next = more && !emitted.isEmpty()
                    ? cursors.encode(cursor.before(source.get(emitted.size() - 1).registryOrdinal())) : null;
            return new ObservabilityDtos.ActivePage(emitted, more, next, observedAt, resume);
        });
        return json(body);
    }

    public ResponseEntity<byte[]> active(@PathVariable String sessionId, HttpServletRequest request)
    {
        require(ObservabilityAccessService.Operation.ACTIVE_READ);
        validateNoQuery(request);
        ObservabilityRuntime runtime = runtime();
        requireLive(runtime);
        return json(pages.writeObject(mapper.active(runtime.activeExecutions().find(sessionId)
                .orElseThrow(ObservabilityRestController::notFound),
                Instant.now(runtime.clock()), runtime.quotas())));
    }

    public ResponseEntity<byte[]> traces(HttpServletRequest request)
    {
        require(ObservabilityAccessService.Operation.TRACE_READ);
        validateQuery(request);
        ObservabilityRuntime runtime = runtime();
        int pageSize = pages.pageSize(single(request, "pageSize"));
        String encoded = single(request, "cursor");
        ObservabilityCursorCodec.Cursor cursor;
        TraceCatalogSlice slice;
        if (encoded == null)
        {
            slice = runtime.traces().list(0, 0, pageSize + 1);
            cursor = ObservabilityCursorCodec.Cursor.initial(runtime.instanceId(), "traces", slice.highWaterOrdinal());
        }
        else
        {
            cursor = cursors.decode(encoded, runtime.instanceId(), "traces");
            long assignedHighWater = runtime.traces().list(0, 0, 1).highWaterOrdinal();
            if (cursor.highWater() == 0 || cursor.beforeOrdinal() == 0
                    || cursor.highWater() > assignedHighWater)
            {
                throw invalidCursor();
            }
            slice = runtime.traces().list(cursor.highWater(), cursor.beforeOrdinal(), pageSize + 1);
        }
        List<FinalizedTraceCatalogEntry> source = slice.entries();
        List<ObservabilityDtos.Trace> items = source.stream().map(mapper::trace).toList();
        Instant observedAt = Instant.now(runtime.clock());
        byte[] body = pages.write(items, pageSize, emitted ->
        {
            boolean more = source.size() > emitted.size();
            String next = more && !emitted.isEmpty()
                    ? cursors.encode(cursor.before(source.get(emitted.size() - 1).catalogOrdinal())) : null;
            return new ObservabilityDtos.Page<>(emitted, more, next, observedAt);
        });
        return json(body);
    }

    public ResponseEntity<byte[]> trace(@PathVariable String traceId, HttpServletRequest request)
    {
        require(ObservabilityAccessService.Operation.TRACE_READ);
        validateNoQuery(request);
        return json(pages.writeObject(mapper.trace(
                runtime().traces().find(traceId).orElseThrow(ObservabilityRestController::notFound))));
    }

    public void fallback(HttpServletRequest request)
    {
        requireGet(request);
        throw notFound();
    }

    private void require(ObservabilityAccessService.Operation operation)
    {
        access.require(operation, SecurityContextHolder.getContext().getAuthentication());
    }

    private ObservabilityRuntime runtime()
    {
        return activation.runtime().orElseThrow(() -> new ObservabilityException(
                404, ObservabilityProblem.Code.NOT_FOUND, "The requested observability resource was not found"));
    }

    private static void requireLive(ObservabilityRuntime runtime)
    {
        if (!runtime.liveMonitoring().isAvailable())
        {
            throw new ObservabilityException(
                    503, ObservabilityProblem.Code.LIVE_MONITORING_UNAVAILABLE,
                    "Live execution monitoring is unavailable");
        }
    }

    private static void validateQuery(HttpServletRequest request)
    {
        requireGet(request);
        if (!Set.of("pageSize", "cursor").containsAll(request.getParameterMap().keySet()))
        {
            throw new ObservabilityException(
                    400, ObservabilityProblem.Code.INVALID_REQUEST, "The request contains an unsupported query parameter");
        }
        request.getParameterMap().forEach((name, values) ->
        {
            if (values == null || values.length != 1)
            {
                throw new ObservabilityException(
                        400, ObservabilityProblem.Code.INVALID_REQUEST,
                        "Query parameters must occur exactly once");
            }
        });
    }

    private static void validateNoQuery(HttpServletRequest request)
    {
        requireGet(request);
        if (!request.getParameterMap().isEmpty())
        {
            throw new ObservabilityException(
                    400, ObservabilityProblem.Code.INVALID_REQUEST,
                    "The request contains an unsupported query parameter");
        }
    }

    private static void requireGet(HttpServletRequest request)
    {
        if (!"GET".equals(request.getMethod()))
        {
            throw new ObservabilityException(
                    400, ObservabilityProblem.Code.INVALID_REQUEST,
                    "The request method or shape is not supported");
        }
        try
        {
            List<MediaType> accepted = MediaType.parseMediaTypes(
                    Collections.list(request.getHeaders("Accept")));
            if (!accepted.isEmpty() && accepted.stream().noneMatch(mediaType ->
                    mediaType.getQualityValue() > 0 && mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)))
            {
                throw invalidRequestShape();
            }
        }
        catch (org.springframework.http.InvalidMediaTypeException ex)
        {
            throw invalidRequestShape();
        }
    }

    private static String single(HttpServletRequest request, String name)
    {
        String[] values = request.getParameterValues(name);
        return values == null ? null : values[0];
    }

    private static ResponseEntity<byte[]> json(byte[] body)
    {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ObservabilityException notFound()
    {
        return new ObservabilityException(
                404, ObservabilityProblem.Code.NOT_FOUND, "The requested observability resource was not found");
    }

    private static ObservabilityException invalidCursor()
    {
        return new ObservabilityException(
                400, ObservabilityProblem.Code.INVALID_CURSOR, "The continuation is invalid");
    }

    private static ObservabilityException invalidRequestShape()
    {
        return new ObservabilityException(
                400, ObservabilityProblem.Code.INVALID_REQUEST, "The request method or shape is not supported");
    }
}
