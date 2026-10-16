package com.db.estate.ops;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fault injection. Called by the triage app's chaos panel and the eval harness — never by the agent.
 * Every service also supports "benign-deploy": a real release bump with no behaviour change, used as
 * a red herring so the agent cannot simply blame the most recent deployment.
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    public static final String BENIGN_DEPLOY = "benign-deploy";

    private final List<ChaosHandler> handlers;
    private final ServiceIdentity identity;

    public ChaosController(ObjectProvider<ChaosHandler> handlers, ServiceIdentity identity) {
        // not every service can be broken (e.g. trade-confirmations), so zero handlers is valid
        this.handlers = handlers.orderedStream().toList();
        this.identity = identity;
    }

    @GetMapping
    public Map<String, Object> state() {
        List<String> faults = new ArrayList<>();
        List<String> active = new ArrayList<>();
        handlers.forEach(h -> {
            faults.addAll(h.faults());
            active.addAll(h.active());
        });
        faults.add(BENIGN_DEPLOY);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", identity.serviceId());
        out.put("release", identity.release());
        out.put("faults", faults);
        out.put("active", active);
        return out;
    }

    @PostMapping("/{fault}")
    public Map<String, Object> inject(@PathVariable String fault) {
        if (fault.equals(BENIGN_DEPLOY)) {
            String next = identity.nextPatch();
            identity.deploy(next, "Release " + next + " — dependency upgrades (Jackson, Netty) and log message tidy-up",
                    "ci-pipeline", () -> {
                    });
            return state();
        }
        ChaosHandler handler = handlers.stream().filter(h -> h.faults().contains(fault)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        identity.serviceId() + " does not support fault " + fault));
        if (!handler.active().contains(fault)) {
            handler.inject(fault);
        }
        return state();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        handlers.forEach(h -> {
            if (!h.active().isEmpty()) {
                h.reset();
            }
        });
        return state();
    }
}
