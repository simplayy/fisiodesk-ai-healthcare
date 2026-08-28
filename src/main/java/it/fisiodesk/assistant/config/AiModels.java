package it.fisiodesk.assistant.config;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import it.fisiodesk.assistant.enrichment.LlmExtractor;

/**
 * Punto unico in cui si scopre quali modelli sono configurati. Spring AI istanzia il provider scelto
 * con spring.ai.model.chat / spring.ai.model.embedding; qui ci si limita a chiedere "c'è?" e a dargli
 * un nome leggibile. Tutto il resto del codice funziona anche con nessun modello.
 */
@Component
public class AiModels {

    private final @Nullable ChatClient client;
    private final @Nullable EmbeddingModel embedding;
    private final Environment env;

    public AiModels(ObjectProvider<ChatModel> chat, ObjectProvider<EmbeddingModel> embedding, Environment env) {
        ChatModel model = chat.getIfAvailable();
        this.client = model == null ? null : ChatClient.create(model);
        this.embedding = embedding.getIfAvailable();
        this.env = env;
    }

    public Optional<ChatClient> chatClient() {
        return Optional.ofNullable(client);
    }

    public Optional<EmbeddingModel> embedding() {
        return Optional.ofNullable(embedding);
    }

    public Optional<LlmExtractor> extractor() {
        return chatClient().map(c -> new LlmExtractor(c, nomeChat().orElse("llm")));
    }

    public Optional<String> nomeChat() {
        return client == null ? Optional.empty() : Optional.of(nome("chat"));
    }

    public Optional<String> nomeEmbedding() {
        return embedding == null ? Optional.empty() : Optional.of(nome("embedding"));
    }

    /** es. "ollama/qwen3.5:4b", ricavato da spring.ai.model.chat e spring.ai.ollama.chat.model. */
    private String nome(String tipo) {
        String provider = env.getProperty("spring.ai.model." + tipo, "?");
        return provider + "/" + env.getProperty("spring.ai." + provider + "." + tipo + ".model", "?");
    }
}
