package com.db.triage.agent;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sends investigation events to /topic/investigation/{id} and records them
 * per incident so the Replay tab can scrub through them afterwards.
 */
@Service
public class InvestigationEventPublisher {

    private final SimpMessagingTemplate messaging;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Map<String, Object>>> history =
            new ConcurrentHashMap<>();

    public InvestigationEventPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void publish(String incidentId, String type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("at", Instant.now().toString());
        event.putAll(payload);
        history.computeIfAbsent(incidentId, k -> new CopyOnWriteArrayList<>()).add(event);
        messaging.convertAndSend("/topic/investigation/" + incidentId, event);
    }

    public void nodeStatus(String incidentId, String service, String status) {
        publish(incidentId, "NODE_STATUS", Map.of("service", service, "status", status));
    }

    public void thought(String incidentId, String text) {
        publish(incidentId, "AGENT_THOUGHT", Map.of("text", text));
    }

    public void toolCall(String incidentId, String tool, Map<String, Object> params, String summary, long ledgerId) {
        publish(incidentId, "TOOL_CALL", Map.of("tool", tool, "params", params, "summary", summary,
                "ledgerId", ledgerId));
    }

    public void hypotheses(String incidentId, List<Map<String, Object>> hypotheses) {
        publish(incidentId, "HYPOTHESIS_UPDATE", Map.of("hypotheses", hypotheses));
    }

    public void reportReady(String incidentId) {
        publish(incidentId, "REPORT_READY", Map.of());
    }

    public List<Map<String, Object>> historyFor(String incidentId) {
        return List.copyOf(history.getOrDefault(incidentId, new CopyOnWriteArrayList<>()));
    }
}
