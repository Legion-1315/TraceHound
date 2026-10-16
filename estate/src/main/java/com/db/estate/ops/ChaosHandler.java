package com.db.estate.ops;

import java.util.List;

/**
 * Implemented by a service that can be broken on purpose. The triage agent never sees these
 * switches — it only sees what the broken behaviour does to logs, metrics and changes.
 */
public interface ChaosHandler {

    /** Fault ids this service supports, e.g. "schema-drift". */
    List<String> faults();

    void inject(String fault);

    /** Restore healthy behaviour. */
    void reset();

    /** Faults currently active. */
    List<String> active();
}
