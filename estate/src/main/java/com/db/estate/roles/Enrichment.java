package com.db.estate.roles;

import com.db.estate.domain.EnrichedTrade;
import com.db.estate.domain.Trade;
import com.db.estate.ops.ChaosHandler;
import com.db.estate.ops.OpsMetrics;
import com.db.estate.ops.SchemaRegistry;
import com.db.estate.ops.ServiceIdentity;
import com.db.estate.web.EstateHttp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * enrichment-service: consumes trades from its inbound queue, enriches them with currency and LEI
 * from ref-data-service, and forwards them to submission-builder.
 * Fault "consumer-lag": a config reload leaves the consumer group fetching zero records.
 */
@RestController
@ConditionalOnProperty(name = "estate.role", havingValue = "enrichment-service")
public class Enrichment implements ChaosHandler {

    private static final Logger log = LoggerFactory.getLogger(Enrichment.class);
    static final String CONSUMER_LAG = "consumer-lag";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {
    };
    private static final int CONSUMERS = 4;

    private final RestClient refData;
    private final RestClient builder;
    private final OpsMetrics metrics;
    private final ServiceIdentity identity;
    private final SchemaRegistry schema;
    private final LinkedBlockingQueue<Trade> queue = new LinkedBlockingQueue<>(50_000);
    private final AtomicInteger threadSeq = new AtomicInteger();
    private ExecutorService consumers;
    private volatile boolean running = true;
    private volatile int maxPollRecords = 500;

    public Enrichment(EstateHttp http, OpsMetrics metrics, ServiceIdentity identity, SchemaRegistry schema,
                      @Value("${estate.urls.ref-data-service}") String refDataUrl,
                      @Value("${estate.urls.submission-builder}") String builderUrl) {
        this.refData = http.client(refDataUrl);
        this.builder = http.client(builderUrl);
        this.metrics = metrics;
        this.identity = identity;
        this.schema = schema;
    }

    @PostConstruct
    void start() {
        schema.register("POST /v1/trades (request)", () -> Map.of("tradeId", "", "isin", "", "venue", "",
                "quantity", 0, "price", 0.0, "account", "", "clientId", "", "executedAt", ""));
        consumers = Executors.newFixedThreadPool(CONSUMERS,
                r -> new Thread(r, "te-enrich-1-consumer-" + threadSeq.incrementAndGet()));
        for (int i = 0; i < CONSUMERS; i++) {
            consumers.submit(this::consumeLoop);
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        consumers.shutdownNow();
    }

    @PostMapping("/v1/trades")
    public ResponseEntity<Void> accept(@RequestBody Trade trade) {
        if (!queue.offer(trade)) {
            log.error("Inbound queue full ({} messages), rejecting trade {}", queue.size(), trade.tradeId());
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.accepted().build();
    }

    private void consumeLoop() {
        while (running) {
            try {
                if (maxPollRecords <= 0) {
                    // a poll that is allowed zero records returns nothing, forever
                    Thread.sleep(500);
                    continue;
                }
                Trade trade = queue.poll(1, TimeUnit.SECONDS);
                if (trade != null) {
                    process(trade);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Unexpected consumer error: {}", e.toString());
            }
        }
    }

    private void process(Trade trade) {
        long start = System.nanoTime();
        MDC.put("corr", trade.correlationId());
        String step = "ref-data-service";
        try {
            Map<String, Object> ccyRef = refData.get().uri("/v1/currencies/{isin}", trade.isin())
                    .retrieve().body(JSON_MAP);
            Object ccy = ccyRef == null ? null : ccyRef.get("ccy");
            if (ccy == null) {
                log.warn("Schema mapping: source field 'ccy' absent in ref-data response for isin={}; currency set to null (mapper fallback) client={} acct={}",
                        trade.isin(), trade.clientId(), trade.account());
            }
            Map<String, Object> leiRef = refData.get().uri("/v1/lei/{clientId}", trade.clientId())
                    .retrieve().body(JSON_MAP);
            String lei = leiRef == null ? null : (String) leiRef.get("lei");

            step = "submission-builder";
            EnrichedTrade enriched = new EnrichedTrade(trade, ccy == null ? null : ccy.toString(), lei);
            builder.post().uri("/v1/reports").body(enriched).retrieve().toBodilessEntity();

            long took = TradeCapture.elapsedMs(start);
            log.info("Enriched trade {} isin={} ccy={} lei={} in {}ms", trade.tradeId(), trade.isin(),
                    ccy, lei, took);
            metrics.record(false, took);
        } catch (HttpStatusCodeException e) {
            log.warn("{} returned HTTP {} for trade {}; trade parked for replay", step, e.getStatusCode().value(),
                    trade.tradeId());
            metrics.record(true, TradeCapture.elapsedMs(start));
        } catch (RestClientException e) {
            log.error("Call to {} failed for trade {}: {}", step, trade.tradeId(), EstateHttp.brief(e));
            metrics.record(true, TradeCapture.elapsedMs(start));
        } finally {
            MDC.remove("corr");
        }
    }

    @Scheduled(initialDelay = 15000, fixedDelay = 15000)
    public void reportLag() {
        int lag = queue.size();
        if (lag > 200) {
            log.warn("Consumer group te-enrich-1 lag={} partitions=6 consumers={} max-poll-records={}",
                    lag, CONSUMERS, maxPollRecords);
        } else {
            log.info("Consumer group te-enrich-1 lag={} partitions=6 consumers={}", lag, CONSUMERS);
        }
    }

    @Override
    public List<String> faults() {
        return List.of(CONSUMER_LAG);
    }

    @Override
    public void inject(String fault) {
        identity.recordChange("CONFIG", "Config reload: te-enrich-1 consumer settings refreshed from config server "
                + "(max-poll-records 500 -> 0)", "ops-bot");
        maxPollRecords = 0;
    }

    @Override
    public void reset() {
        maxPollRecords = 500;
        identity.recordChange("CONFIG", "Config reload: te-enrich-1 max-poll-records restored to 500", "ops-bot");
    }

    @Override
    public List<String> active() {
        return maxPollRecords <= 0 ? List.of(CONSUMER_LAG) : List.of();
    }
}
