package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.skillapi.SkillExecutionView;
import com.lokiscale.bifrost.skillapi.SkillTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class SampleController {

    private static final Logger log = LoggerFactory.getLogger(SampleController.class);

    private final SkillTemplate skillTemplate;

    public SampleController(SkillTemplate skillTemplate) {
        this.skillTemplate = skillTemplate;
    }

    @GetMapping(value = "/expenses", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object getExpenses() {
        return skillTemplate.invoke("getLatestExpenses", Map.of());
    }

    @GetMapping(value = "/invoice/parse", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object parseInvoice(@RequestParam String filePath) throws IOException {
        // Read invoice data from file
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }
        
        String invoiceText = FileCopyUtils.copyToString(new FileReader(file));
        
        long startedAtNanos = System.nanoTime();
        ViewHolder holder = new ViewHolder();
        String result = skillTemplate.invoke("invoiceParser", Map.of("payload", invoiceText), holder::set);
        log.info("Completed invoiceParser sessionId={} filePath={} elapsedMs={}",
                holder.view == null ? "unknown" : holder.view.sessionId(),
                filePath,
                elapsedMillis(startedAtNanos));
        return buildExecutionResponse(result, filePath, holder.view);
    }

    @GetMapping(value = "/invoice/check-duplicate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object checkDuplicateInvoice(@RequestParam String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }
        
        String invoiceText = FileCopyUtils.copyToString(new FileReader(file));
        
        long startedAtNanos = System.nanoTime();
        ViewHolder holder = new ViewHolder();
        String result = skillTemplate.invoke("duplicateInvoiceChecker", Map.of("payload", invoiceText), holder::set);
        log.info("Completed duplicateInvoiceChecker sessionId={} filePath={} elapsedMs={}",
                holder.view == null ? "unknown" : holder.view.sessionId(),
                filePath,
                elapsedMillis(startedAtNanos));
        return buildExecutionResponse(result, filePath, holder.view);
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private Map<String, Object> buildExecutionResponse(String result, String filePath, SkillExecutionView executionView) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("filePath", filePath);
        if (executionView != null) {
            response.put("sessionId", executionView.sessionId());
            response.put("executionJournal", executionView.executionJournal());
        }
        return response;
    }

    private static final class ViewHolder {
        private SkillExecutionView view;

        private void set(SkillExecutionView view) {
            this.view = view;
        }
    }
}
