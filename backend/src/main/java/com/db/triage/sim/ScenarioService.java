package com.db.triage.sim;

import com.db.triage.model.ScenarioDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ScenarioService {

    private final Map<String, ScenarioDef> scenarios = new LinkedHashMap<>();

    public ScenarioService() {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:scenarios/*.yaml");
            for (Resource r : resources) {
                ScenarioDef def = yaml.readValue(r.getInputStream(), ScenarioDef.class);
                scenarios.put(def.id(), def);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load scenarios", e);
        }
    }

    public List<ScenarioDef> all() {
        return List.copyOf(scenarios.values());
    }

    public Optional<ScenarioDef> byId(String id) {
        return Optional.ofNullable(scenarios.get(id));
    }
}
