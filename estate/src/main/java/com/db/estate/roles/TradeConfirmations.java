package com.db.estate.roles;

import com.db.estate.domain.EstateData;
import com.db.estate.domain.Trade;
import com.db.estate.ops.OpsMetrics;
import com.db.estate.web.EstateHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * trade-confirmations: the second business flow. Dispatches client confirmations, which need the
 * settlement currency and LEI from ref-data-service — so it shares ref-data's blast radius.
 */
@Component
@ConditionalOnProperty(name = "estate.role", havingValue = "trade-confirmations")
public class TradeConfirmations {

    private static final Logger log = LoggerFactory.getLogger(TradeConfirmations.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient refData;
    private final OpsMetrics metrics;

    public TradeConfirmations(EstateHttp http, OpsMetrics metrics,
                              @Value("${estate.urls.ref-data-service}") String refDataUrl) {
        this.refData = http.client(refDataUrl);
        this.metrics = metrics;
    }

    @Scheduled(initialDelay = 6000, fixedRateString = "${estate.confirmation-interval-ms:1000}")
    public void dispatchConfirmation() {
        Trade trade = EstateData.randomTrade();
        long start = System.nanoTime();
        MDC.put("corr", trade.correlationId());
        try {
            Map<String, Object> ccyRef = refData.get().uri("/v1/currencies/{isin}", trade.isin())
                    .retrieve().body(JSON_MAP);
            Map<String, Object> leiRef = refData.get().uri("/v1/lei/{clientId}", trade.clientId())
                    .retrieve().body(JSON_MAP);
            Object ccy = ccyRef == null ? null : ccyRef.get("ccy");
            if (ccy == null) {
                log.warn("Confirmation for trade {} held for manual review: settlement currency missing from ref-data response client={}",
                        trade.tradeId(), trade.clientId());
                metrics.record(true, TradeCapture.elapsedMs(start));
                return;
            }
            log.info("Confirmation dispatched trade={} client={} ccy={} lei={} channel=SWIFT", trade.tradeId(),
                    trade.clientId(), ccy, leiRef == null ? null : leiRef.get("lei"));
            metrics.record(false, TradeCapture.elapsedMs(start));
        } catch (RestClientException e) {
            log.error("Confirmation for trade {} failed: ref-data-service call failed: {}", trade.tradeId(),
                    EstateHttp.brief(e));
            metrics.record(true, TradeCapture.elapsedMs(start));
        } finally {
            MDC.remove("corr");
        }
    }
}
