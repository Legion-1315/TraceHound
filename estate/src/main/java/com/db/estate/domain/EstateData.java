package com.db.estate.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Static reference data and trade generation shared by the services. */
public final class EstateData {

    public record Instrument(String isin, String currency, String name) {
    }

    public static final List<Instrument> INSTRUMENTS = List.of(
            new Instrument("DE000BAY0017", "EUR", "Bayer AG"),
            new Instrument("FR0000120271", "EUR", "TotalEnergies SE"),
            new Instrument("NL0011821202", "EUR", "ING Groep NV"),
            new Instrument("GB00BH4HKS39", "GBP", "Vodafone Group plc"),
            new Instrument("DE0007164600", "EUR", "SAP SE"),
            new Instrument("IT0003128367", "EUR", "Enel SpA"),
            new Instrument("ES0113900J37", "EUR", "Banco Santander SA"));

    private static final Map<String, Instrument> BY_ISIN = INSTRUMENTS.stream()
            .collect(Collectors.toUnmodifiableMap(Instrument::isin, i -> i));

    private static final List<String> VENUES = List.of("XETR", "XPAR", "XAMS", "XLON", "XMIL", "XMAD");
    private static final AtomicLong TRADE_SEQ = new AtomicLong(700_000 + ThreadLocalRandom.current().nextInt(50_000));

    private EstateData() {
    }

    public static Instrument instrument(String isin) {
        return BY_ISIN.get(isin);
    }

    public static Trade randomTrade() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Instrument inst = INSTRUMENTS.get(rnd.nextInt(INSTRUMENTS.size()));
        return new Trade(
                "TR-" + TRADE_SEQ.incrementAndGet(),
                newCorrelationId(),
                inst.isin(),
                VENUES.get(rnd.nextInt(VENUES.size())),
                100 * (1 + rnd.nextInt(50)),
                Math.round((10 + rnd.nextDouble() * 190) * 100.0) / 100.0,
                "ACC-" + (10_000_000 + rnd.nextInt(89_999_999)),
                "CLI-" + (100_000 + rnd.nextInt(899_999)),
                Instant.now());
    }

    public static String newCorrelationId() {
        return String.format("c-%08x", ThreadLocalRandom.current().nextInt());
    }

    /** Deterministic synthetic LEI for a client id (format 5299 + 9 digits + N). */
    public static String leiFor(String clientId) {
        long h = Math.abs((long) clientId.hashCode()) % 1_000_000_000L;
        return String.format("5299%09dN", h);
    }
}
