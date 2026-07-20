package com.db.triage.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * File-backed store of past incidents with TF-IDF cosine similarity search.
 * Powers the learning-loop demo: a repeat incident is recognised from its symptoms.
 */
@Service
public class IncidentMemoryService {

    public record PastIncident(String id, Instant when, String symptoms, String investigationPath,
                               String rootCause, String resolution) {
    }

    public record Match(PastIncident incident, double similarity) {
    }

    private final File store;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<PastIncident> incidents = new ArrayList<>();

    public IncidentMemoryService(@Value("${triage.memory-file}") String memoryFile) {
        this.store = new File(memoryFile);
        if (store.exists()) {
            try {
                PastIncident[] loaded = json.readValue(store, PastIncident[].class);
                incidents.addAll(List.of(loaded));
            } catch (IOException ignored) {
                // corrupt store: start fresh, demo must not crash
            }
        }
    }

    public synchronized void record(PastIncident incident) {
        incidents.add(incident);
        try {
            store.getParentFile().mkdirs();
            json.writerWithDefaultPrettyPrinter().writeValue(store, incidents);
        } catch (IOException ignored) {
            // persistence failure must never break an investigation
        }
    }

    public synchronized List<Match> search(String symptomsText, int topK) {
        if (incidents.isEmpty()) return List.of();
        List<Map<String, Double>> docVectors = new ArrayList<>();
        List<String> corpus = new ArrayList<>();
        for (PastIncident pi : incidents) {
            corpus.add(pi.symptoms() + " " + pi.rootCause());
        }
        corpus.add(symptomsText);
        Map<String, Double> idf = idf(corpus);
        for (String doc : corpus) {
            docVectors.add(tfidf(doc, idf));
        }
        Map<String, Double> queryVec = docVectors.get(docVectors.size() - 1);
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < incidents.size(); i++) {
            double sim = cosine(queryVec, docVectors.get(i));
            if (sim > 0.05) {
                matches.add(new Match(incidents.get(i), Math.round(sim * 100.0) / 100.0));
            }
        }
        matches.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return matches.size() > topK ? matches.subList(0, topK) : matches;
    }

    public synchronized int size() {
        return incidents.size();
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() > 2) tokens.add(t);
        }
        return tokens;
    }

    private static Map<String, Double> idf(List<String> corpus) {
        Map<String, Integer> docFreq = new HashMap<>();
        for (String doc : corpus) {
            tokenize(doc).stream().distinct().forEach(t -> docFreq.merge(t, 1, Integer::sum));
        }
        Map<String, Double> idf = new HashMap<>();
        int n = corpus.size();
        docFreq.forEach((term, df) -> idf.put(term, Math.log(1.0 + (double) n / df)));
        return idf;
    }

    private static Map<String, Double> tfidf(String doc, Map<String, Double> idf) {
        Map<String, Integer> tf = new HashMap<>();
        List<String> tokens = tokenize(doc);
        tokens.forEach(t -> tf.merge(t, 1, Integer::sum));
        Map<String, Double> vec = new HashMap<>();
        tf.forEach((term, count) -> vec.put(term, count * idf.getOrDefault(term, 0.0)));
        return vec;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0, na = 0, nb = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) {
            dot += e.getValue() * b.getOrDefault(e.getKey(), 0.0);
            na += e.getValue() * e.getValue();
        }
        for (double v : b.values()) nb += v * v;
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
