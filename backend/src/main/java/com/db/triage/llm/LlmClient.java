package com.db.triage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Minimal Messages-API client, behind an interface so the agent loop can be tested without the network.
 * Requests and replies use the Anthropic Messages format whatever the provider; see {@link LlmConfig}.
 */
public interface LlmClient {

    /** True when the client can be used (for Anthropic: an API key is configured). */
    boolean configured();

    String model();

    /** "anthropic" or "ollama"; shown in the UI next to the model. */
    default String provider() {
        return "anthropic";
    }

    /** False for a local model: its tokens cost nothing, so they do not count against the spend cap. */
    default boolean billed() {
        return true;
    }

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
