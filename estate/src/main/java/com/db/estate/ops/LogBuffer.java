package com.db.estate.ops;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory ring buffer of this process's own log events, filled by {@link RingBufferAppender}.
 * Static because Logback instantiates the appender before the Spring context exists.
 */
public final class LogBuffer {

    public record LogRecord(Instant timestamp, String level, String correlationId, String logger, String message) {
    }

    private static final int MAX = 20_000;
    private static final ConcurrentLinkedDeque<LogRecord> RECORDS = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger SIZE = new AtomicInteger();

    private LogBuffer() {
    }

    public static void add(LogRecord record) {
        RECORDS.addLast(record);
        if (SIZE.incrementAndGet() > MAX) {
            if (RECORDS.pollFirst() != null) {
                SIZE.decrementAndGet();
            }
        }
    }

    /** Records newer than {@code since}, newest first, optionally filtered to a minimum level. */
    public static List<LogRecord> since(Instant since, String minLevel, int limit) {
        int min = rank(minLevel);
        List<LogRecord> out = new ArrayList<>();
        Iterator<LogRecord> it = RECORDS.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            LogRecord r = it.next();
            if (r.timestamp().isBefore(since)) {
                break;
            }
            if (rank(r.level()) >= min) {
                out.add(r);
            }
        }
        return out;
    }

    public static void clear() {
        RECORDS.clear();
        SIZE.set(0);
    }

    static int rank(String level) {
        if (level == null) return 0;
        return switch (level.toUpperCase()) {
            case "TRACE" -> 0;
            case "DEBUG" -> 1;
            case "INFO" -> 2;
            case "WARN" -> 3;
            case "ERROR" -> 4;
            default -> 0;
        };
    }
}
