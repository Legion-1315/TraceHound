package com.db.triage.telemetry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Collapses a window of log lines into message patterns (ids and numbers masked) with counts and
 * first/last-seen times — what an SRE does by eye in Splunk. Keeps the agent's context small and
 * makes "this WARN started 12 minutes ago, 140 times" visible at a glance.
 */
public final class LogSummarizer {

    public record Line(Instant timestamp, String level, String correlationId, String message) {
    }

    public record PatternCount(String level, String pattern, int count, Instant firstSeen, Instant lastSeen,
                               String example) {
    }

    public record Summary(int total, Map<String, Integer> byLevel, List<PatternCount> patterns,
                          List<Line> recentProblems, List<Line> recent) {
    }

    private static final List<Map.Entry<Pattern, String>> MASKS = List.of(
            Map.entry(Pattern.compile("\\bTR-\\d+\\b"), "TR-<id>"),
            Map.entry(Pattern.compile("\\bARM-\\d+\\b"), "ARM-<id>"),
            Map.entry(Pattern.compile("\\b[A-Z]{2}[A-Z0-9]{9}\\d\\b"), "<isin>"),
            Map.entry(Pattern.compile("\\bc-[0-9a-f]{8}\\b"), "<corr>"),
            Map.entry(Pattern.compile("\\b[0-9a-f]{16,}\\b"), "<hex>"),
            Map.entry(Pattern.compile("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z"), "<ts>"),
            Map.entry(Pattern.compile("\\b\\d+(\\.\\d+)?\\b"), "<n>"));

    private LogSummarizer() {
    }

    public static String mask(String message) {
        String out = message;
        for (Map.Entry<Pattern, String> m : MASKS) {
            out = m.getKey().matcher(out).replaceAll(m.getValue());
        }
        return out;
    }

    /** @param lines newest first */
    public static Summary summarize(List<Line> lines, int maxPatterns, int maxProblems, int maxRecent) {
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        Map<String, PatternCount> patterns = new LinkedHashMap<>();
        List<Line> problems = new ArrayList<>();
        for (Line l : lines) {
            byLevel.merge(l.level(), 1, Integer::sum);
            String key = l.level() + "|" + mask(l.message());
            PatternCount pc = patterns.get(key);
            if (pc == null) {
                patterns.put(key, new PatternCount(l.level(), mask(l.message()), 1, l.timestamp(), l.timestamp(),
                        l.message()));
            } else {
                Instant first = l.timestamp().isBefore(pc.firstSeen()) ? l.timestamp() : pc.firstSeen();
                Instant last = l.timestamp().isAfter(pc.lastSeen()) ? l.timestamp() : pc.lastSeen();
                patterns.put(key, new PatternCount(pc.level(), pc.pattern(), pc.count() + 1, first, last, pc.example()));
            }
            if ((l.level().equals("ERROR") || l.level().equals("WARN")) && problems.size() < maxProblems) {
                problems.add(l);
            }
        }
        List<PatternCount> ranked = new ArrayList<>(patterns.values());
        ranked.sort(Comparator.comparingInt((PatternCount p) -> severity(p.level())).reversed()
                .thenComparing(Comparator.comparingInt(PatternCount::count).reversed()));
        return new Summary(lines.size(), byLevel, ranked.subList(0, Math.min(maxPatterns, ranked.size())),
                problems, lines.subList(0, Math.min(maxRecent, lines.size())));
    }

    private static int severity(String level) {
        return switch (level) {
            case "ERROR" -> 4;
            case "WARN" -> 3;
            case "INFO" -> 2;
            case "DEBUG" -> 1;
            default -> 0;
        };
    }
}
