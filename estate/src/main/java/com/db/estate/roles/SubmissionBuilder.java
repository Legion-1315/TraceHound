package com.db.estate.roles;

import com.db.estate.domain.EnrichedTrade;
import com.db.estate.ops.ChaosHandler;
import com.db.estate.ops.OpsMetrics;
import com.db.estate.ops.ServiceIdentity;
import com.db.estate.web.EstateHttp;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * submission-builder: builds and seals an ESMA RTS-22 report per enriched trade on a bounded
 * "sealing" thread pool, then forwards it to regulatory-gateway.
 * Fault "thread-pool-exhaustion": release 5.3.0 shrinks the pool and makes sealing slower,
 * so under normal load the queue fills and new reports are rejected.
 */
@RestController
@ConditionalOnProperty(name = "estate.role", havingValue = "submission-builder")
public class SubmissionBuilder implements ChaosHandler {

    private static final Logger log = LoggerFactory.getLogger(SubmissionBuilder.class);
    static final String THREAD_POOL_EXHAUSTION = "thread-pool-exhaustion";

    private record PoolConfig(int threads, int queueCapacity, int sealMs) {
    }

    private static final PoolConfig HEALTHY = new PoolConfig(4, 200, 20);
    private static final PoolConfig RELEASE_5_3_0 = new PoolConfig(1, 20, 700);

    private final RestClient gateway;
    private final OpsMetrics metrics;
    private final ServiceIdentity identity;
    private final AtomicInteger threadSeq = new AtomicInteger();
    private volatile PoolConfig config = HEALTHY;
    private volatile ThreadPoolExecutor pool;

    public SubmissionBuilder(EstateHttp http, OpsMetrics metrics, ServiceIdentity identity,
                             @Value("${estate.urls.regulatory-gateway}") String gatewayUrl) {
        this.gateway = http.client(gatewayUrl);
        this.metrics = metrics;
        this.identity = identity;
        this.pool = newPool(HEALTHY);
    }

    private ThreadPoolExecutor newPool(PoolConfig c) {
        return new ThreadPoolExecutor(c.threads(), c.threads(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(c.queueCapacity()),
                r -> new Thread(r, "rts22-sealer-" + threadSeq.incrementAndGet()));
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    @PostMapping("/v1/reports")
    public ResponseEntity<Void> build(@RequestBody EnrichedTrade enriched) {
        long received = System.nanoTime();
        ThreadPoolExecutor p = pool;
        try {
            p.execute(() -> buildAndForward(enriched, received));
            return ResponseEntity.accepted().build();
        } catch (RejectedExecutionException e) {
            log.error("Sealing pool saturated: active={}/{} queue={}/{} — rejected report for {}",
                    p.getActiveCount(), p.getMaximumPoolSize(), p.getQueue().size(), config.queueCapacity(),
                    enriched.trade().tradeId());
            metrics.record(true, TradeCapture.elapsedMs(received));
            return ResponseEntity.status(503).build();
        }
    }

    private void buildAndForward(EnrichedTrade enriched, long received) {
        String tradeId = enriched.trade().tradeId();
        MDC.put("corr", enriched.trade().correlationId());
        try {
            long queueWaitMs = TradeCapture.elapsedMs(received);
            if (enriched.currency() == null) {
                log.info("Report built for {} with 1 null optional-mapped field (currency) — passthrough enabled", tradeId);
            } else {
                log.info("RTS-22 report built for {} fields=65/65 populated", tradeId);
            }
            Thread.sleep(config.sealMs()); // checksum + sealing
            try {
                gateway.post().uri("/v1/submissions").body(enriched).retrieve().toBodilessEntity();
                log.info("Report {} sealed and forwarded to regulatory-gateway (queue wait {}ms)", tradeId, queueWaitMs);
                metrics.record(false, TradeCapture.elapsedMs(received));
            } catch (HttpClientErrorException e) {
                // the gateway judged the content; the builder did its job
                log.warn("regulatory-gateway rejected report {}: HTTP {}", tradeId, e.getStatusCode().value());
                metrics.record(false, TradeCapture.elapsedMs(received));
            } catch (RestClientException e) {
                log.error("Forwarding report {} to regulatory-gateway failed: {}", tradeId, EstateHttp.brief(e));
                metrics.record(true, TradeCapture.elapsedMs(received));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            MDC.remove("corr");
        }
    }

    @Scheduled(initialDelay = 15000, fixedDelay = 15000)
    public void poolStats() {
        ThreadPoolExecutor p = pool;
        int q = p.getQueue().size();
        if (q > config.queueCapacity() * 0.8) {
            log.warn("Sealing pool active={}/{} queue={}/{} completed={}", p.getActiveCount(),
                    p.getMaximumPoolSize(), q, config.queueCapacity(), p.getCompletedTaskCount());
        } else {
            log.info("Sealing pool active={}/{} queue={}/{} completed={}", p.getActiveCount(),
                    p.getMaximumPoolSize(), q, config.queueCapacity(), p.getCompletedTaskCount());
        }
    }

    private void swapPool(PoolConfig c) {
        ThreadPoolExecutor old = pool;
        config = c;
        pool = newPool(c);
        old.shutdown(); // let in-flight reports finish
    }

    @Override
    public List<String> faults() {
        return List.of(THREAD_POOL_EXHAUSTION);
    }

    @Override
    public void inject(String fault) {
        identity.deploy("5.3.0", "Release 5.3.0 — RTS-22 sealing switched to SHA-512 checksums; executor sizing "
                + "now read from shared ops profile", "k.schmidt", () -> swapPool(RELEASE_5_3_0));
    }

    @Override
    public void reset() {
        identity.deploy(identity.nextPatch(), "Hotfix: sealing executor sizing restored (4 threads, queue 200)",
                "k.schmidt", () -> swapPool(HEALTHY));
    }

    @Override
    public List<String> active() {
        return config == HEALTHY ? List.of() : List.of(THREAD_POOL_EXHAUSTION);
    }
}
