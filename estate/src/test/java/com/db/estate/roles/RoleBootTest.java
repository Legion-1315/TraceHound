package com.db.estate.roles;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every service profile must boot on its own and expose /ops and /chaos. Peers are not running in
 * these tests, so outbound calls fail — fine; we only check each process wires up correctly.
 */
abstract class RoleBootTest {

    static final String QUIET = "estate.change-noise.enabled=false";

    @Autowired
    TestRestTemplate http;

    @SuppressWarnings("unchecked")
    void assertBoots(String service) {
        Map<String, Object> health = http.getForObject("/ops/health", Map.class);
        assertEquals(service, health.get("service"));
        Map<String, Object> chaos = http.getForObject("/chaos", Map.class);
        assertTrue(((List<String>) chaos.get("faults")).contains("benign-deploy"));
    }
}
