package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.core.BifrostSessionRunner;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import com.lokiscale.bifrost.core.ExecutionJournal;
import com.lokiscale.bifrost.core.SkillThoughtTrace;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
public class SampleController {

    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityExecutionRouter executionRouter;
    private final BifrostSessionRunner sessionRunner;

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
            // Log the start of invoice parsing
            session.logThought(Instant.now(), "Starting invoice parsing from file: " + filePath + " (text length: " + invoiceText.length() + ")");
            
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
            
            return response;
        });
    }
}
