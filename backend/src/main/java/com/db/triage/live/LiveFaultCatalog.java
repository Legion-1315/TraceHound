package com.db.triage.live;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/** The faults the chaos panel and eval harness can inject into the live estate (live-faults.yaml). */
@Service
public class LiveFaultCatalog {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(String service, String fault) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fault(String id, String name, String category, String description, List<Action> actions,
                        @JsonProperty("expected_root_cause_service") String expectedRootCauseService,
                        @JsonProperty("expected_keywords") List<String> expectedKeywords) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Catalog(List<Fault> faults) {
    }

    private final List<Fault> faults;

    public LiveFaultCatalog() {
        try {
            Catalog c = new ObjectMapper(new YAMLFactory())
                    .readValue(new ClassPathResource("live-faults.yaml").getInputStream(), Catalog.class);
            this.faults = List.copyOf(c.faults());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load live-faults.yaml", e);
        }
    }

    public List<Fault> all() {
        return faults;
    }

    public Optional<Fault> byId(String id) {
        return faults.stream().filter(f -> f.id().equals(id)).findFirst();
    }
}
