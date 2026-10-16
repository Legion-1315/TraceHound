package com.db.triage.live;

import com.db.triage.telemetry.EstateChaosClient;
import com.db.triage.topology.TopologyService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies catalogue faults to the live estate and reports what is currently broken. */
@Service
public class LiveChaosService {

    private final LiveFaultCatalog catalog;
    private final EstateChaosClient chaos;
    private final TopologyService topology;

    public LiveChaosService(LiveFaultCatalog catalog, EstateChaosClient chaos, TopologyService topology) {
        this.catalog = catalog;
        this.chaos = chaos;
        this.topology = topology;
    }

    public void inject(LiveFaultCatalog.Fault fault) {
        for (LiveFaultCatalog.Action a : fault.actions()) {
            chaos.inject(a.service(), a.fault());
        }
    }

    /** Restore every service to healthy behaviour. Returns per-service errors, if any. */
    public Map<String, String> resetAll() {
        Map<String, String> errors = new LinkedHashMap<>();
        topology.topology().services().forEach(s -> {
            try {
                chaos.reset(s.id());
            } catch (RuntimeException e) {
                errors.put(s.id(), e.getMessage());
            }
        });
        return errors;
    }

    /** Wipe logs/metrics/change history everywhere (eval runs start clean). */
    public Map<String, String> clearHistoryAll() {
        Map<String, String> errors = new LinkedHashMap<>();
        topology.topology().services().forEach(s -> {
            try {
                chaos.clearHistory(s.id());
            } catch (RuntimeException e) {
                errors.put(s.id(), e.getMessage());
            }
        });
        return errors;
    }

    /** Catalogue faults whose actions are all currently active on the estate. */
    @SuppressWarnings("unchecked")
    public List<String> activeFaults() {
        Map<String, List<String>> activeByService = new LinkedHashMap<>();
        topology.topology().services().forEach(s -> {
            try {
                Object active = chaos.state(s.id()).get("active");
                activeByService.put(s.id(), active instanceof List<?> l ? (List<String>) l : List.of());
            } catch (RuntimeException e) {
                activeByService.put(s.id(), List.of());
            }
        });
        List<String> out = new ArrayList<>();
        for (LiveFaultCatalog.Fault f : catalog.all()) {
            boolean on = f.actions().stream()
                    .filter(a -> !a.fault().equals("benign-deploy"))
                    .allMatch(a -> activeByService.getOrDefault(a.service(), List.of()).contains(a.fault()));
            if (on) out.add(f.id());
        }
        return out;
    }
}
