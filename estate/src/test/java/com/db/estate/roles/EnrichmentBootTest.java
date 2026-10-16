package com.db.estate.roles;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = RoleBootTest.QUIET)
@ActiveProfiles("enrichment-service")
class EnrichmentBootTest extends RoleBootTest {

    @Test
    void boots() {
        assertBoots("enrichment-service");
    }
}
