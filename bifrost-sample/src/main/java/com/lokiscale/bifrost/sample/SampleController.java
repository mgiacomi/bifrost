package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.core.BifrostSession;
import com.lokiscale.bifrost.core.BifrostSessionRunner;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.ExecutionJournal;
import com.lokiscale.bifrost.core.SkillThoughtTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class SampleController {

    private static final Logger log = LoggerFactory.getLogger(SampleController.class);

    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityExecutionRouter executionRouter;
    private final BifrostSessionRunner sessionRunner;
    private final Map<String, BifrostSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, BifrostSession> recentSessions = new ConcurrentHashMap<>();

    public SampleController(CapabilityRegistry capabilityRegistry,
                            CapabilityExecutionRouter executionRouter,
                            BifrostSessionRunner sessionRunner) {
        this.capabilityRegistry = capabilityRegistry;
        this.executionRouter = executionRouter;
        this.sessionRunner = sessionRunner;
    }

    @GetMapping(value = "/expenses", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object getExpenses() {
        CapabilityMetadata metadata = capabilityRegistry.getCapability("getLatestExpenses");
        if (metadata == null) {
            throw new IllegalArgumentException("Skill 'getLatestExpenses' not found");
        }

        return sessionRunner.callWithNewSession(session ->
            executionRouter.execute(metadata, Map.of(), session, null)
        );
    }

    @GetMapping(value = "/debug/bifrost/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object listTrackedSessions() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("activeSessionIds", activeSessions.keySet());
        response.put("recentSessionIds", recentSessions.keySet());
        return response;
    }

    @GetMapping(value = "/debug/bifrost/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object getTrackedSession(@PathVariable String sessionId,
                                    @RequestParam(defaultValue = "invoiceParser") String route) {
        BifrostSession session = requireTrackedSession(sessionId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", session.getSessionId());
        response.put("status", activeSessions.containsKey(sessionId) ? "active" : "recent");
        response.put("frames", session.getFramesSnapshot());
        response.put("executionPlan", session.getExecutionPlanSnapshot());
        response.put("lastLinterOutcome", session.getLastLinterOutcomeSnapshot());
        response.put("lastOutputSchemaOutcome", session.getLastOutputSchemaOutcomeSnapshot());
        response.put("sessionUsage", session.getSessionUsageSnapshot());
        response.put("thoughts", session.getSkillThoughts(route));
        return response;
    }

    @GetMapping(value = "/debug/bifrost/sessions/{sessionId}/journal", produces = MediaType.APPLICATION_JSON_VALUE)
    public ExecutionJournal getTrackedSessionJournal(@PathVariable String sessionId) {
        return requireTrackedSession(sessionId).getExecutionJournal();
    }

    @GetMapping(value = "/debug/bifrost/sessions/{sessionId}/thoughts", produces = MediaType.APPLICATION_JSON_VALUE)
    public SkillThoughtTrace getTrackedSessionThoughts(@PathVariable String sessionId,
                                                       @RequestParam(defaultValue = "invoiceParser") String route) {
        return requireTrackedSession(sessionId).getSkillThoughts(route);
    }

    @GetMapping(value = "/invoice/parse", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object parseInvoice(@RequestParam String filePath) throws IOException {
        // Read invoice data from file
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }
        
        String invoiceText = FileCopyUtils.copyToString(new FileReader(file));
        
        CapabilityMetadata metadata = capabilityRegistry.getCapability("invoiceParser");
        if (metadata == null) {
            throw new IllegalArgumentException("Skill 'invoiceParser' not found");
        }

        return sessionRunner.callWithNewSession(session -> {
            activeSessions.put(session.getSessionId(), session);
            long startedAtNanos = System.nanoTime();
            // Log the start of invoice parsing
            session.logThought(Instant.now(), "Starting invoice parsing from file: " + filePath + " (text length: " + invoiceText.length() + ")");
            log.info("Starting invoiceParser sessionId={} filePath={} textLength={}",
                    session.getSessionId(),
                    filePath,
                    invoiceText.length());

            try {
                Object result = executionRouter.execute(metadata, Map.of("payload", invoiceText), session, null);

                // Capture journal for debugging/monitoring
                ExecutionJournal journal = session.getExecutionJournal();
                SkillThoughtTrace thoughts = session.getSkillThoughts("invoiceParser");

                // Return result along with journal data
                Map<String, Object> response = new HashMap<>();
                response.put("result", result);
                response.put("journal", journal);
                response.put("thoughts", thoughts);
                response.put("sessionId", session.getSessionId());
                response.put("filePath", filePath);

                recentSessions.put(session.getSessionId(), session);
                log.info("Completed invoiceParser sessionId={} filePath={} elapsedMs={} journalEntries={}",
                        session.getSessionId(),
                        filePath,
                        elapsedMillis(startedAtNanos),
                        journal.getEntriesSnapshot().size());
                return response;
            } catch (RuntimeException ex) {
                session.logError(Instant.now(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
                recentSessions.put(session.getSessionId(), session);
                log.error("Failed invoiceParser sessionId={} filePath={} elapsedMs={}",
                        session.getSessionId(),
                        filePath,
                        elapsedMillis(startedAtNanos),
                        ex);
                throw ex;
            } finally {
                activeSessions.remove(session.getSessionId());
            }
        });
    }

    private BifrostSession requireTrackedSession(String sessionId) {
        BifrostSession session = activeSessions.get(sessionId);
        if (session != null) {
            return session;
        }
        session = recentSessions.get(sessionId);
        if (session != null) {
            return session;
        }
        throw new IllegalArgumentException("Tracked session '" + sessionId + "' not found");
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
