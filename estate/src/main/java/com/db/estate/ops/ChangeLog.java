package com.db.estate.ops;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Deployments, config changes and maintenance this service went through (its slice of the CMDB). */
@Component
public class ChangeLog {

    public record ChangeEvent(Instant timestamp, String serviceId, String type, String summary, String author) {
    }

    private final CopyOnWriteArrayList<ChangeEvent> events = new CopyOnWriteArrayList<>();

    public void record(ChangeEvent event) {
        events.add(event);
    }

    /** Events newer than {@code since}, newest first. */
    public List<ChangeEvent> since(Instant since) {
        List<ChangeEvent> out = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0; i--) {
            ChangeEvent e = events.get(i);
            if (!e.timestamp().isBefore(since)) {
                out.add(e);
            }
        }
        return out;
    }

    public void clear() {
        events.clear();
    }
}
