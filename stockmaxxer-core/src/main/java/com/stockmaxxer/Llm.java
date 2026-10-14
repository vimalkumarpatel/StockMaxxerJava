package com.stockmaxxer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Claude, behind a replay cache. The model is not reproducible, but the system is: every answer is saved as
 * {@code data/llm-cache/<sha256>.json}, keyed by the model, the prompt version and the exact prompt, and a
 * cached answer is always reused. With replay-only on, a cache miss is an error instead of an API call.
 */
@Component
public class Llm {

    /** One risk the model found: a type from the closed list, severity 0-5, firm or industry, and a quote. */
    public record Event(String type, int severity, String scope, String quote) {}

    public record Events(List<Event> events) {}

    /** What a cache file holds: the model that actually answered, and its answer. */
    public record Answer(String key, String model, Events events) {}

    private record CacheFile(String model, Events events) {}

    private final Path cacheDir;
    private final boolean replayOnly;
    private final String model;
    private final String apiKey;
    private final ObjectMapper json = new ObjectMapper();
    private AnthropicClient client;   // built on the first live call, so nothing needs a key to start

    public Llm(@Value("${SM_DATA_DIR:data}") String dataDir,
               @Value("${SM_LLM_REPLAY:false}") boolean replayOnly,
               @Value("${SM_LLM_MODEL:claude-sonnet-5-5}") String model,
               @Value("${SM_ANTHROPIC_API_KEY:${ANTHROPIC_API_KEY:}}") String apiKey) {
        this.cacheDir = Path.of(dataDir, "llm-cache");
        this.replayOnly = replayOnly;
        this.model = model;
        this.apiKey = apiKey;
    }

    /** The cache key: sha256 of the model, the prompt version and the prompt. */
    public String key(String prompt) {
        return sha256(model + "\n" + Prompts.VERSION + "\n" + prompt);
    }

    /** The events in a prompt: from the cache if there, otherwise from Claude (then cached). */
    public Answer extract(String prompt) {
        String key = key(prompt);
        Path file = cacheDir.resolve(key + ".json");
        try {
            if (Files.exists(file)) {
                CacheFile cached = json.readValue(file.toFile(), CacheFile.class);
                return new Answer(key, cached.model(), cached.events());
            }
            if (replayOnly) {
                throw new IllegalStateException("replay only (SM_LLM_REPLAY): no cached answer " + key);
            }
            StructuredMessage<Events> response = call(prompt);
            Events events = checked(response.stopReason().map(Object::toString).orElse(""),
                    response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst().orElse(null));
            String usedModel = response.model().asString();
            Files.createDirectories(cacheDir);
            json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), new CacheFile(usedModel, events));
            return new Answer(key, usedModel, events);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read or write " + file, e);
        }
    }

    /**
     * Only a complete answer may be cached. A cut-off or refused answer would replay forever as "no risks",
     * which looks exactly like good news, so it is an error instead.
     */
    static Events checked(String stopReason, Events events) {
        if (!stopReason.equals("end_turn")) {
            throw new IllegalStateException("incomplete answer (stop reason " + stopReason + "): not cached");
        }
        if (events == null || events.events() == null) {
            throw new IllegalStateException("empty answer: not cached");
        }
        return events;
    }

    /** The request: structured output, so the answer is always an Events object. No temperature: the cache gives determinism. */
    StructuredMessageCreateParams<Events> params(String prompt) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .outputConfig(Events.class)
                .addUserMessage(prompt)
                .build();
    }

    private StructuredMessage<Events> call(String prompt) {
        if (client == null) {
            if (apiKey.isBlank()) {
                throw new IllegalStateException("set SM_ANTHROPIC_API_KEY to call Claude (or SM_LLM_REPLAY=1 to use the cache only)");
            }
            client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        }
        return client.messages().create(params(prompt));
    }

    static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
