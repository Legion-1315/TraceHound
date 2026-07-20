package com.db.triage.redaction;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrubs simulated PII from telemetry before it reaches the agent (LLM or scripted).
 * Counts redactions per incident so the UI can show "PII items redacted: N".
 */
@Service
public class RedactionService {

    private record Rule(Pattern pattern, String replacement) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("ACC-\\d{8}"), "[REDACTED:ACCOUNT]"),
            new Rule(Pattern.compile("CLI-\\d{6}"), "[REDACTED:CLIENT]"),
            new Rule(Pattern.compile("\\b5299\\d{9}N\\b"), "[REDACTED:LEI]"));

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public String scrub(String incidentId, String text) {
        String result = text;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(result);
            int hits = 0;
            while (m.find()) hits++;
            if (hits > 0) {
                result = m.replaceAll(rule.replacement());
                counters.computeIfAbsent(incidentId, k -> new AtomicInteger()).addAndGet(hits);
            }
        }
        return result;
    }

    public int count(String incidentId) {
        AtomicInteger c = counters.get(incidentId);
        return c == null ? 0 : c.get();
    }
}
