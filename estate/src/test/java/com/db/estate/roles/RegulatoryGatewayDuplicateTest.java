package com.db.estate.roles;

import com.db.estate.domain.EnrichedTrade;
import com.db.estate.domain.EstateData;
import com.db.estate.domain.Trade;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The gateway must report a transaction reference once, even when two copies arrive at the same time
 * (the duplicate-trades fault sends both within milliseconds, on different threads).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = RoleBootTest.QUIET)
@ActiveProfiles("regulatory-gateway")
class RegulatoryGatewayDuplicateTest {

    /** A slow fake ARM (plain HTTP), so both copies are in flight before either is acknowledged. */
    private static final HttpServer ARM;
    private static final AtomicInteger ARM_REPORTS = new AtomicInteger();

    static {
        try {
            ARM = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        ARM.setExecutor(Executors.newFixedThreadPool(4));
        ARM.createContext("/arm/v1/reports", exchange -> {
            ARM_REPORTS.incrementAndGet();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"ack\":\"ARM-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        ARM.start();
    }

    @DynamicPropertySource
    static void armUrl(DynamicPropertyRegistry registry) {
        registry.add("estate.urls.regulator-arm", () -> "http://localhost:" + ARM.getAddress().getPort());
    }

    @AfterAll
    static void stopArm() {
        ARM.stop(0);
    }

    @Autowired
    TestRestTemplate http;

    @Test
    void twoCopiesArrivingTogetherAreReportedOnce() throws Exception {
        Trade trade = EstateData.randomTrade();
        EnrichedTrade report = new EnrichedTrade(trade, "EUR", "5299000000000000000X");
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return http.postForEntity("/v1/submissions", report, String.class).getStatusCode().value();
                }));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : results) codes.add(f.get());
            codes.sort(null);
            assertEquals(List.of(202, 409), codes);
            assertEquals(1, ARM_REPORTS.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
