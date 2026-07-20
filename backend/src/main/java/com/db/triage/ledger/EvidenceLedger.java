package com.db.triage.ledger;

import com.db.triage.model.LedgerEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Append-only, per-incident evidence ledger. Report citations reference entry ids. */
@Service
public class EvidenceLedger {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<LedgerEntry>> entries = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    public LedgerEntry append(String incidentId, String tool, Map<String, Object> params,
                              String resultSummary, String agentInference) {
        LedgerEntry entry = new LedgerEntry(seq.getAndIncrement(), Instant.now(), tool, params,
                resultSummary, agentInference);
        entries.computeIfAbsent(incidentId, k -> new CopyOnWriteArrayList<>()).add(entry);
        return entry;
    }

    public List<LedgerEntry> forIncident(String incidentId) {
        return List.copyOf(entries.getOrDefault(incidentId, new CopyOnWriteArrayList<>()));
    }

    public boolean exists(String incidentId, long ledgerId) {
        return forIncident(incidentId).stream().anyMatch(e -> e.id() == ledgerId);
    }
}
