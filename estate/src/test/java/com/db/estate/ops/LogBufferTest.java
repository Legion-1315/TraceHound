package com.db.estate.ops;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogBufferTest {

    @Test
    void returnsNewestFirstFilteredByLevelAndTime() {
        LogBuffer.clear();
        Instant t0 = Instant.parse("2026-10-16T10:00:00Z");
        LogBuffer.add(new LogBuffer.LogRecord(t0, "INFO", "-", "x", "old"));
        LogBuffer.add(new LogBuffer.LogRecord(t0.plusSeconds(60), "WARN", "-", "x", "warn"));
        LogBuffer.add(new LogBuffer.LogRecord(t0.plusSeconds(61), "INFO", "-", "x", "info"));

        List<LogBuffer.LogRecord> all = LogBuffer.since(t0.plusSeconds(30), "TRACE", 10);
        assertEquals(List.of("info", "warn"), all.stream().map(LogBuffer.LogRecord::message).toList());

        List<LogBuffer.LogRecord> warn = LogBuffer.since(t0, "WARN", 10);
        assertEquals(List.of("warn"), warn.stream().map(LogBuffer.LogRecord::message).toList());
        LogBuffer.clear();
    }
}
