package com.db.estate.roles;

import com.db.estate.domain.EstateData;
import com.db.estate.domain.Trade;
import com.db.estate.ops.ChaosHandler;
import com.db.estate.ops.OpsMetrics;
import com.db.estate.ops.ServiceIdentity;
import com.db.estate.web.EstateHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * trade-capture: captures executed trades and publishes each one to enrichment-service.
 * Fault "duplicate-trades": a fast-path batching flag republishes every trade.
 */
@Component
@ConditionalOnProperty(name = "estate.role", havingValue = "trade-capture")
public class TradeCapture implements ChaosHandler {

    private static final Logger log = LoggerFactory.getLogger(TradeCapture.class);
    static final String DUPLICATE_TRADES = "duplicate-trades";

    private final RestClient enrichment;
    private final OpsMetrics metrics;
    private final ServiceIdentity identity;
    private final AtomicLong sequence = new AtomicLong(500_000);
    private volatile boolean fastpathBatching;

    public TradeCapture(EstateHttp http, OpsMetrics metrics, ServiceIdentity identity,
                        @Value("${estate.urls.enrichment-service}") String enrichmentUrl) {
        this.enrichment = http.client(enrichmentUrl);
        this.metrics = metrics;
        this.identity = identity;
    }

    @Scheduled(initialDelay = 5000, fixedRateString = "${estate.trade-interval-ms:500}")
    public void captureTrade() {
        Trade trade = EstateData.randomTrade();
        MDC.put("corr", trade.correlationId());
        try {
            log.info("Trade {} captured venue={} isin={} qty={} px={} acct={}", trade.tradeId(), trade.venue(),
                    trade.isin(), trade.quantity(), trade.price(), trade.account());
            publish(trade);
            if (fastpathBatching) {
                // the fast path re-sends the batch it just sent; downstream sees the same trade twice
                publish(trade);
            }
        } finally {
            MDC.remove("corr");
        }
    }

    private void publish(Trade trade) {
        long start = System.nanoTime();
        try {
            enrichment.post().uri("/v1/trades").body(trade).retrieve().toBodilessEntity();
            log.info("Published trade {} to enrichment-service seq={}", trade.tradeId(), sequence.incrementAndGet());
            metrics.record(false, elapsedMs(start));
        } catch (RestClientException e) {
            log.error("Publish of trade {} to enrichment-service failed: {}", trade.tradeId(), EstateHttp.brief(e));
            metrics.record(true, elapsedMs(start));
        }
    }

    @Override
    public List<String> faults() {
        return List.of(DUPLICATE_TRADES);
    }

    @Override
    public void inject(String fault) {
        identity.recordChange("CONFIG", "Feature flag 'fastpath-batching' enabled via ops console", "ops-bot");
        fastpathBatching = true;
    }

    @Override
    public void reset() {
        fastpathBatching = false;
        identity.recordChange("CONFIG", "Feature flag 'fastpath-batching' disabled via ops console", "ops-bot");
    }

    @Override
    public List<String> active() {
        return fastpathBatching ? List.of(DUPLICATE_TRADES) : List.of();
    }

    static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
