package com.db.triage.telemetry;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogSummarizerTest {

    @Test
    void masksIdsAndNumbersIntoPatterns() {
        assertEquals("Submission TR-<id> rejected [rule ESMA-RTS22-F31] in <n>ms",
                LogSummarizer.mask("Submission TR-712345 rejected [rule ESMA-RTS22-F31] in 34ms"));
        assertEquals("GET /v1/currencies/<isin> <n> in <n>ms",
                LogSummarizer.mask("GET /v1/currencies/DE000BAY0017 200 in 4ms"));
    }

    @Test
    void groupsByPatternWithCountsAndRanksProblemsFirst() {
        Instant t = Instant.parse("2026-10-16T10:00:00Z");
        List<LogSummarizer.Line> lines = List.of( // newest first
                new LogSummarizer.Line(t.plusSeconds(30), "INFO", "c-1", "Enriched trade TR-3 in 4ms"),
                new LogSummarizer.Line(t.plusSeconds(20), "WARN", "c-2", "field 'ccy' absent for TR-2"),
                new LogSummarizer.Line(t.plusSeconds(10), "WARN", "c-3", "field 'ccy' absent for TR-1"),
                new LogSummarizer.Line(t, "INFO", "c-4", "Enriched trade TR-0 in 3ms"));
        LogSummarizer.Summary s = LogSummarizer.summarize(lines, 10, 10, 2);
        assertEquals(4, s.total());
        assertEquals(2, s.byLevel().get("WARN"));
        assertEquals(2, s.patterns().size());
        LogSummarizer.PatternCount top = s.patterns().get(0);
        assertEquals("WARN", top.level());
        assertEquals(2, top.count());
        assertEquals(t.plusSeconds(10), top.firstSeen());
        assertEquals(t.plusSeconds(20), top.lastSeen());
        assertEquals(2, s.recentProblems().size());
        assertEquals(2, s.recent().size());
    }
}
