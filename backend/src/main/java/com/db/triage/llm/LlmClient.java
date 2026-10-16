package com.db.triage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Minimal Messages-API client, behind an interface so the agent loop can be tested without the network. */
public interface LlmClient {

    /** True when an API key is configured. */
    boolean configured();

    String model();

    /** POST /v1/messages with {@code body} (model is filled in by the client). */
    JsonNode createMessage(ObjectNode body) throws LlmException;

    class LlmException extends Exception {
        private final int status;

        public LlmException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
