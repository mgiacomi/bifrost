package com.lokiscale.bifrost.sample;

import com.lokiscale.bifrost.core.BifrostSessionRunner;
import com.lokiscale.bifrost.core.CapabilityExecutionRouter;
import com.lokiscale.bifrost.core.CapabilityMetadata;
import com.lokiscale.bifrost.core.CapabilityRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping(value = "/invoice/parse", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object parseInvoice(@RequestBody String invoiceText) {
        CapabilityMetadata metadata = capabilityRegistry.getCapability("invoiceParser");
        if (metadata == null) {
            throw new IllegalArgumentException("Skill 'invoiceParser' not found");
        }

        return sessionRunner.callWithNewSession(session ->
            executionRouter.execute(metadata, Map.of("payload", invoiceText), session, null)
        );
    }
}
