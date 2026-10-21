package com.db.triage.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Locale;

/**
 * Picks the model behind the agent: {@code LLM_PROVIDER=anthropic} (default, needs ANTHROPIC_API_KEY)
 * or {@code LLM_PROVIDER=ollama} (a local model, free, no key).
 */
@Configuration
public class LlmConfig {

    @Bean
    LlmClient llmClient(@Value("${triage.llm.provider:anthropic}") String provider,
                        @Value("${triage.anthropic.api-key:}") String apiKey,
                        @Value("${triage.anthropic.model}") String anthropicModel,
                        @Value("${triage.anthropic.base-url:https://api.anthropic.com}") String anthropicUrl,
                        @Value("${triage.ollama.base-url:http://localhost:11434}") String ollamaUrl,
                        @Value("${triage.ollama.model:qwen3:8b}") String ollamaModel,
                        @Value("${triage.ollama.num-ctx:16384}") int ollamaNumCtx,
                        @Value("${triage.ollama.think:false}") boolean ollamaThink,
                        @Value("${triage.ollama.timeout-seconds:900}") int ollamaTimeoutSeconds) {
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "anthropic", "" -> new AnthropicHttpClient(apiKey, anthropicModel, anthropicUrl);
            case "ollama" -> new OllamaClient(ollamaUrl, ollamaModel, ollamaNumCtx, ollamaThink,
                    Duration.ofSeconds(ollamaTimeoutSeconds));
            default -> throw new IllegalArgumentException(
                    "Unknown LLM_PROVIDER '" + provider + "'. Use 'anthropic' or 'ollama'.");
        };
    }
}
