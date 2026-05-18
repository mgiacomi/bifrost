package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.skillapi.SkillExecutionView;
import com.lokiscale.bifrost.skillapi.SkillTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
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
    private final ResourceLoader resourceLoader;

    public SampleController(SkillTemplate skillTemplate, ResourceLoader resourceLoader) {
        this.skillTemplate = skillTemplate;
        this.resourceLoader = resourceLoader;
    }

    @GetMapping(value = "/expenses", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object getExpenses() {
        return skillTemplate.invoke("getLatestExpenses", Map.of());
    }

    @GetMapping(value = "/feedstock/parse-sample", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object parseSampleFeedstockTicket() {
        long startedAtNanos = System.nanoTime();
        ViewHolder holder = new ViewHolder();
        String result = skillTemplate.invoke("feedstockTicketParser", Map.of(), holder::set);
        log.info("Completed feedstockTicketParser sessionId={} elapsedMs={}",
                holder.view == null ? "unknown" : holder.view.sessionId(),
                elapsedMillis(startedAtNanos));
        return buildExecutionResponse(result, "classpath:/forms/feedstock-p1.jpg", holder.view);
    }

    @GetMapping(value = "/feedstock/parse-sample-by-skill", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object parseSampleFeedstockTicketBySkill() {
        Resource image = resourceLoader.getResource("classpath:/forms/feedstock-p1.jpg");
        long startedAtNanos = System.nanoTime();
        ViewHolder holder = new ViewHolder();
        String result = skillTemplate.invoke("feedstockTicketParserBySkill", Map.of("image", image), holder::set);
        log.info("Completed feedstockTicketParserBySkill sessionId={} elapsedMs={}",
                holder.view == null ? "unknown" : holder.view.sessionId(),
                elapsedMillis(startedAtNanos));
        return buildExecutionResponse(result, "classpath:/forms/feedstock-p1.jpg", holder.view);
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
