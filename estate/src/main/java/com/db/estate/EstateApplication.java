package com.db.estate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One artifact, many services. Each process is started with
 * {@code --spring.profiles.active=<service-id>} and only that service's beans are created
 * (see the {@code @ConditionalOnProperty(name = "estate.role")} on each role class).
 *
 * The services talk to each other over real HTTP, fail for real when a fault is injected,
 * and expose their own telemetry under {@code /ops/*}. Faults are switched on via
 * {@code /chaos/*}, which the triage agent's tools never read.
 */
@SpringBootApplication
@EnableScheduling
public class EstateApplication {

    public static void main(String[] args) {
        SpringApplication.run(EstateApplication.class, args);
    }
}
