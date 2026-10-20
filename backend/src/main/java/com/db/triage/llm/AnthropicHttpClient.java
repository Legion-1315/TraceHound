package com.db.triage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * Calls the Anthropic Messages API. The key comes from ANTHROPIC_API_KEY (environment or the
 * git-ignored .env file) and is only ever sent in the x-api-key header — never logged, never
 * returned by any endpoint, never sent to the browser. Created by {@link LlmConfig}.
 */
public class AnthropicHttpClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicHttpClient.class);
    private static final Set<Integer> RETRYABLE = Set.of(408, 409, 429, 500, 502, 503, 504, 529);
    private static final int MAX_ATTEMPTS = 3;

    private final String apiKey;
    private final String model;
    private final URI endpoint;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public AnthropicHttpClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/messages");
    }

    @Override
    public boolean configured() {
        return !apiKey.isEmpty();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public JsonNode createMessage(ObjectNode body) throws LlmException {
        if (!configured()) {
            throw new LlmException(0, "ANTHROPIC_API_KEY is not set");
        }
        body.put("model", model);
        String payload;
        try {
            payload = json.writeValueAsString(body);
        } catch (IOException e) {
            throw new LlmException(0, "Could not serialise request: " + e.getMessage());
        }
        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) {
                    return json.readTree(response.body());
                }
                last = new LlmException(status, describe(status, response.body()));
                if (!RETRYABLE.contains(status) || attempt == MAX_ATTEMPTS) {
                    throw last;
                }
                long waitMs = response.headers().firstValue("retry-after")
                        .map(AnthropicHttpClient::seconds).orElse(attempt * 3L) * 1000;
                log.warn("Anthropic API {} (attempt {}/{}), retrying in {}ms", status, attempt, MAX_ATTEMPTS, waitMs);
                Thread.sleep(Math.min(waitMs, 20_000));
            } catch (IOException e) {
                last = new LlmException(0, "Network error calling the Anthropic API: " + e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    throw last;
                }
                sleepQuietly(attempt * 2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException(0, "Interrupted");
            }
        }
        throw last;
    }

    private String describe(int status, String body) {
        String detail = "";
        try {
            JsonNode err = json.readTree(body).path("error");
            detail = err.path("type").asText("") + ": " + err.path("message").asText("");
        } catch (IOException ignored) {
            // non-JSON error body
        }
        return switch (status) {
            case 401 -> "Anthropic API rejected the API key (401). Check ANTHROPIC_API_KEY in your .env.";
            case 403 -> "Anthropic API refused the request (403) " + detail;
            case 404 -> "Model '" + model + "' not found (404). Check ANTHROPIC_MODEL. " + detail;
            case 429 -> "Anthropic API rate limit hit (429). " + detail;
            case 529 -> "Anthropic API overloaded (529). " + detail;
            default -> "Anthropic API error " + status + " " + detail;
        };
    }

    private static long seconds(String header) {
        try {
            return Math.max(1, (long) Math.ceil(Double.parseDouble(header)));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
