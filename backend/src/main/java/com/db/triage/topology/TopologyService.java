package com.db.triage.topology;

import com.db.triage.model.Topology;
import com.db.triage.model.TriageReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TopologyService {

    private final Topology topology;
    private final Map<String, Topology.ServiceDef> byId;

    public TopologyService() {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try {
            this.topology = yaml.readValue(new ClassPathResource("topology.yaml").getInputStream(), Topology.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load topology.yaml", e);
        }
        this.byId = topology.services().stream()
                .collect(Collectors.toMap(Topology.ServiceDef::id, Function.identity()));
    }

    public Topology topology() {
        return topology;
    }

    public Optional<Topology.ServiceDef> service(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Topology.FlowDef> flow(String id) {
        return topology.flows().stream().filter(f -> f.id().equals(id)).findFirst();
    }

    /** Services participating in a flow, plus their direct upstream dependencies (e.g. ref-data feeding enrichment). */
    public List<Topology.ServiceDef> subgraphForFlow(String flowId) {
        Topology.FlowDef flow = flow(flowId).orElseThrow(() -> new IllegalArgumentException("Unknown flow " + flowId));
        List<Topology.ServiceDef> result = new ArrayList<>();
        for (String id : flow.path()) {
            Topology.ServiceDef s = byId.get(id);
            if (s != null && !result.contains(s)) {
                result.add(s);
            }
        }
        // pull in direct upstream feeders not on the main path (ref-data-service for mifid-reporting)
        for (String id : flow.path()) {
            Topology.ServiceDef s = byId.get(id);
            if (s == null) continue;
            for (String up : s.upstream()) {
                Topology.ServiceDef feeder = byId.get(up);
                if (feeder != null && !result.contains(feeder)) {
                    result.add(feeder);
                }
            }
        }
        return result;
    }

    /** Which flows (and their consuming services/teams) are exposed to a fault in the given service. */
    public List<TriageReport.BlastRadiusEntry> blastRadius(String faultyServiceId) {
        List<TriageReport.BlastRadiusEntry> entries = new ArrayList<>();
        for (Topology.FlowDef flow : topology.flows()) {
            boolean onPath = flow.path().contains(faultyServiceId);
            List<Topology.ServiceDef> consumers = topology.services().stream()
                    .filter(s -> s.upstream().contains(faultyServiceId) && s.businessFlows().contains(flow.id()))
                    .toList();
            if (!onPath && consumers.isEmpty()) {
                continue;
            }
            if (consumers.isEmpty()) {
                entries.add(new TriageReport.BlastRadiusEntry(flow.id(), flow.name(), flow.criticality(),
                        faultyServiceId, byId.get(faultyServiceId).team()));
            } else {
                for (Topology.ServiceDef consumer : consumers) {
                    entries.add(new TriageReport.BlastRadiusEntry(flow.id(), flow.name(), flow.criticality(),
                            consumer.id(), consumer.team()));
                }
            }
        }
        return entries;
    }
}
