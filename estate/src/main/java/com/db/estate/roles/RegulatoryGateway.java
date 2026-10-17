package com.db.estate.roles;

import com.db.estate.domain.EnrichedTrade;
import com.db.estate.ops.ChaosHandler;
import com.db.estate.ops.OpsMetrics;
import com.db.estate.ops.ServiceIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * regulatory-gateway: validates each report against ESMA RTS-22 rules, de-duplicates by transaction
 * reference, and submits it to the regulator's ARM endpoint over mutual TLS.
 * Fault "cert-expiry": the gateway's client certificate is no longer valid (the clock caught up with
 * it), so every TLS handshake with the ARM fails. Nothing was deployed; nothing changed upstream.
 */
@RestController
@ConditionalOnProperty(name = "estate.role", havingValue = "regulatory-gateway")
public class RegulatoryGateway implements ChaosHandler {

    private static final Logger log = LoggerFactory.getLogger(RegulatoryGateway.class);
    static final String CERT_EXPIRY = "cert-expiry";
    private static final char[] PASSWORD = "changeit".toCharArray();

    private record TlsClient(HttpClient http, X509Certificate certificate) {
    }

    private final OpsMetrics metrics;
    private final ServiceIdentity identity;
    private final ObjectMapper json;
    private final URI armUri;
    private final TlsClient current;
    private final TlsClient lapsed;
    private final Set<String> reported = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 100_000;
                }
            }));
    private final AtomicLong lastReceived = new AtomicLong(System.currentTimeMillis());
    private volatile boolean certificateLapsed;

    public RegulatoryGateway(OpsMetrics metrics, ServiceIdentity identity, ObjectMapper json,
                             @Value("${estate.urls.regulator-arm}") String armUrl) throws Exception {
        this.metrics = metrics;
        this.identity = identity;
        this.json = json;
        this.armUri = URI.create(armUrl + "/arm/v1/reports");
        this.current = tlsClient("certs/gateway-client.p12");
        this.lapsed = tlsClient("certs/gateway-client-expired.p12");
    }

    @PostMapping("/v1/submissions")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody EnrichedTrade report) {
        long start = System.nanoTime();
        lastReceived.set(System.currentTimeMillis());
        String tradeId = report.trade().tradeId();

        if (report.currency() == null) {
            log.error("Submission {} rejected by validator: mandatory field 'currency' missing (path $.trade.currency) [rule ESMA-RTS22-F31]",
                    tradeId);
            metrics.record(true, TradeCapture.elapsedMs(start));
            return ResponseEntity.unprocessableEntity().body(Map.of("rule", "ESMA-RTS22-F31"));
        }
        // Claim the reference before calling the ARM: a check-then-add after the call let two copies of
        // the same trade, arriving a few ms apart on different threads, both pass and both be reported.
        if (!reported.add(tradeId)) {
            log.error("Submission {} rejected: duplicate transaction reference, already reported today [rule ESMA-RTS22-F01]",
                    tradeId);
            metrics.record(true, TradeCapture.elapsedMs(start));
            return ResponseEntity.status(409).body(Map.of("rule", "ESMA-RTS22-F01"));
        }

        TlsClient client = certificateLapsed ? lapsed : current;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("transactionReference", tradeId);
            payload.put("isin", report.trade().isin());
            payload.put("currency", report.currency());
            payload.put("quantity", report.trade().quantity());
            payload.put("price", report.trade().price());
            payload.put("lei", report.lei());
            HttpRequest request = HttpRequest.newBuilder(armUri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                JsonNode ack = json.readTree(response.body());
                log.info("Submission {} accepted by ARM ack={}", tradeId, ack.path("ack").asText());
                metrics.record(false, TradeCapture.elapsedMs(start));
                return ResponseEntity.accepted().build();
            }
            reported.remove(tradeId); // not reported, so a resubmission must be allowed
            log.error("ARM returned HTTP {} for submission {}", response.statusCode(), tradeId);
            metrics.record(true, TradeCapture.elapsedMs(start));
            return ResponseEntity.status(502).build();
        } catch (IOException e) {
            reported.remove(tradeId);
            log.error("Submission {} failed: TLS/transport error calling {}: {}", tradeId,
                    armUri.getHost() + ":" + armUri.getPort(), rootMessage(e));
            metrics.record(true, TradeCapture.elapsedMs(start));
            return ResponseEntity.status(502).build();
        } catch (InterruptedException e) {
            reported.remove(tradeId);
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).build();
        }
    }

    @Scheduled(initialDelay = 20000, fixedRate = 15000)
    public void submissionWatchdog() {
        long silentFor = (System.currentTimeMillis() - lastReceived.get()) / 1000;
        if (silentFor >= 30) {
            log.warn("No submissions received from submission-builder in last {}s (expected ~120/min)", silentFor);
        }
    }

    /** Certificate inventory, as a gateway would report it on every new TLS session. */
    @Scheduled(initialDelay = 5000, fixedRate = 60000)
    public void certificateInventory() {
        X509Certificate cert = (certificateLapsed ? lapsed : current).certificate();
        log.info("TLS client certificate in use for {}: subject=\"{}\" serial={} notBefore={} notAfter={}",
                armUri.getHost(), cert.getSubjectX500Principal().getName(), cert.getSerialNumber().toString(16),
                cert.getNotBefore().toInstant(), cert.getNotAfter().toInstant());
    }

    private static TlsClient tlsClient(String keystorePath) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new ClassPathResource(keystorePath).getInputStream()) {
            keyStore.load(in, PASSWORD);
        }
        X509Certificate cert = null;
        for (String alias : Collections.list(keyStore.aliases())) {
            if (keyStore.isKeyEntry(alias)) {
                cert = (X509Certificate) keyStore.getCertificate(alias);
            }
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASSWORD);

        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = new ClassPathResource("certs/truststore.p12").getInputStream()) {
            trust.load(in, PASSWORD);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        HttpClient http = HttpClient.newBuilder()
                .sslContext(ssl)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        return new TlsClient(http, cert);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    @Override
    public List<String> faults() {
        return List.of(CERT_EXPIRY);
    }

    @Override
    public void inject(String fault) {
        // no change record: certificates expire by themselves
        certificateLapsed = true;
        certificateInventory();
    }

    @Override
    public void reset() {
        certificateLapsed = false;
        identity.recordChange("CONFIG", "TLS client certificate for ARM connection renewed and rotated",
                "pki-automation");
        certificateInventory();
    }

    @Override
    public List<String> active() {
        return certificateLapsed ? List.of(CERT_EXPIRY) : List.of();
    }
}
