package com.db.estate.roles;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The regulator only serves mutual TLS; here we just check the HTTPS server and controller come up. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("regulator-arm")
class RegulatorArmBootTest {

    @Autowired
    ApplicationContext context;

    @Test
    void boots() {
        assertTrue(context.containsBean("regulatorArm"));
    }
}
