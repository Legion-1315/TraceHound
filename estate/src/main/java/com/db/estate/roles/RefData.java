package com.db.estate.roles;

import com.db.estate.domain.EstateData;
import com.db.estate.ops.ChaosHandler;
import com.db.estate.ops.OpsMetrics;
import com.db.estate.ops.SchemaRegistry;
import com.db.estate.ops.ServiceIdentity;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ref-data-service: currency and LEI reference data for enrichment and confirmations.
 * Fault "schema-drift": release 2.4.1 renames the currency field 'ccy' to 'currencyCode'.
 * Fault "latency-spike": an online index rebuild makes currency lookups slow (no errors).
 */
@RestController
@ConditionalOnProperty(name = "estate.role", havingValue = "ref-data-service")
public class RefData implements ChaosHandler {

    private static final Logger log = LoggerFactory.getLogger(RefData.class);
    static final String SCHEMA_DRIFT = "schema-drift";
    static final String LATENCY_SPIKE = "latency-spike";

    private final OpsMetrics metrics;
    private final ServiceIdentity identity;
    private final SchemaRegistry schema;
    private volatile boolean isoFieldNames;
    private volatile boolean indexRebuild;

    public RefData(OpsMetrics metrics, ServiceIdentity identity, SchemaRegistry schema) {
        this.metrics = metrics;
        this.identity = identity;
        this.schema = schema;
    }

    @PostConstruct
    void registerSchemas() {
        schema.register("GET /v1/currencies/{isin}", () -> currencyBody(EstateData.INSTRUMENTS.get(0)));
        schema.register("GET /v1/lei/{clientId}", () -> leiBody("CLI-100000"));
    }

    @GetMapping("/v1/currencies/{isin}")
    public ResponseEntity<Map<String, Object>> currency(@PathVariable String isin,
                                                        @RequestHeader(value = "X-Caller", defaultValue = "unknown") String caller) {
        long start = System.nanoTime();
        EstateData.Instrument inst = EstateData.instrument(isin);
        if (inst == null) {
            log.warn("GET /v1/currencies/{} 404 unknown ISIN caller={}", isin, caller);
            metrics.record(false, TradeCapture.elapsedMs(start));
            return ResponseEntity.notFound().build();
        }
        if (indexRebuild) {
            int ms = 900 + ThreadLocalRandom.current().nextInt(700);
            sleep(ms);
            log.warn("Slow query on CCY_REF took {}ms (isin={})", ms, isin);
        }
        Map<String, Object> body = currencyBody(inst);
        long took = TradeCapture.elapsedMs(start);
        log.info("GET /v1/currencies/{} 200 in {}ms caller={}", isin, took, caller);
        metrics.record(false, took);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/v1/lei/{clientId}")
    public Map<String, Object> lei(@PathVariable String clientId,
                                   @RequestHeader(value = "X-Caller", defaultValue = "unknown") String caller) {
        long start = System.nanoTime();
        Map<String, Object> body = leiBody(clientId);
        long took = TradeCapture.elapsedMs(start);
        log.info("GET /v1/lei/{} 200 in {}ms caller={}", clientId, took, caller);
        metrics.record(false, took);
        return body;
    }

    private Map<String, Object> currencyBody(EstateData.Instrument inst) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("isin", inst.isin());
        body.put(isoFieldNames ? "currencyCode" : "ccy", inst.currency());
        body.put("instrumentName", inst.name());
        body.put("minorUnits", 2);
        return body;
    }

    private static Map<String, Object> leiBody(String clientId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("lei", EstateData.leiFor(clientId));
        body.put("status", "ISSUED");
        return body;
    }

    @Override
    public List<String> faults() {
        return List.of(SCHEMA_DRIFT, LATENCY_SPIKE);
    }

    @Override
    public void inject(String fault) {
        if (fault.equals(SCHEMA_DRIFT)) {
            identity.deploy("2.4.1", "Release 2.4.1 — refactor: currency DTO aligned to ISO 4217 naming conventions",
                    "j.mueller", () -> isoFieldNames = true);
        } else if (fault.equals(LATENCY_SPIKE)) {
            identity.recordChange("MAINTENANCE", "DB maintenance: online index rebuild started on CCY_REF (est. 45m)",
                    "dba-scheduler");
            indexRebuild = true;
        }
    }

    @Override
    public void reset() {
        if (isoFieldNames) {
            identity.deploy(identity.nextPatch(), "Rollback: currency DTO field names restored to pre-2.4.1 contract",
                    "j.mueller", () -> isoFieldNames = false);
        }
        if (indexRebuild) {
            indexRebuild = false;
            identity.recordChange("MAINTENANCE", "DB maintenance: index rebuild on CCY_REF completed", "dba-scheduler");
        }
    }

    @Override
    public List<String> active() {
        List<String> out = new ArrayList<>();
        if (isoFieldNames) out.add(SCHEMA_DRIFT);
        if (indexRebuild) out.add(LATENCY_SPIKE);
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
