package it.fisiodesk.assistant.config;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("assistant")
public record AssistantProperties(
        /** Data "oggi" usata per i filtri temporali. Se assente si usa la data più recente presente in eventi_calendario. */
        @Nullable String referenceDate,
        @DefaultValue Planner planner,
        @DefaultValue Enrichment enrichment,
        @DefaultValue Retrieval retrieval) {

    public record Planner(
            /** Oltre questo tempo si risponde con il piano a regole e si lascia finire l'LLM in background. */
            @DefaultValue("1500ms") Duration timeout,
            @DefaultValue("1000") int cacheSize,
            @DefaultValue("6h") Duration cacheTtl,
            /** Domande pre-pianificate all'avvio, così la prima ricerca non paga la latenza del modello. */
            @DefaultValue List<String> warmup) {
    }

    public record Enrichment(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1") int parallelism,
            @DefaultValue("60s") Duration reconcileInterval,
            @DefaultValue("30s") Duration startupDelay) {
    }

    public record Retrieval(
            /** Note restituite da $vectorSearch nella modalità semantica (ordinate per affinità). */
            @DefaultValue("20") int vectorLimit,
            @DefaultValue("0.60") double similarityThreshold) {
    }
}
