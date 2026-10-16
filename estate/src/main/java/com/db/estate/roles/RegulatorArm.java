package com.db.estate.roles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The regulator's Approved Reporting Mechanism (ARM) — outside the bank's estate. HTTPS only, and it
 * requires a valid client certificate (mutual TLS), so an expired gateway certificate fails the handshake.
 */
@RestController
@ConditionalOnProperty(name = "estate.role", havingValue = "regulator-arm")
public class RegulatorArm {

    private static final Logger log = LoggerFactory.getLogger(RegulatorArm.class);
    private final AtomicLong ack = new AtomicLong(9_000_000);

    @PostMapping("/arm/v1/reports")
    public Map<String, Object> receive(@RequestBody Map<String, Object> report) {
        String ackId = "ARM-" + ack.incrementAndGet();
        log.info("Report {} received, ack={}", report.get("transactionReference"), ackId);
        return Map.of("ack", ackId, "status", "RECEIVED");
    }
}
