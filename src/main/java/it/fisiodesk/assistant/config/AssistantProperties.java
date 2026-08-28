package it.fisiodesk.assistant.config;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("assistant")
public record AssistantProperties(
        /** Data "oggi" usata per i filtri temporali. Se assente si usa la data più recente presente in eventi_calendario. */
        @Nullable String referenceDate,
        /** Tempo massimo per rispondere: oltre questo, si risponde con quello che si è riusciti a calcolare. */
        @DefaultValue("2s") Duration budget,
        @DefaultValue Planner planner,
        @DefaultValue Enrichment enrichment,
        @DefaultValue Retrieval retrieval) {

    public record Planner(
            /** Oltre questo tempo si risponde con quanto estratto dalle regole e il modello finisce in background. */
            @DefaultValue("1500ms") Duration timeout,
            /** Le domande fuori vocabolario, una volta interpretate dal modello, non si ripagano. */
            @DefaultValue("1000") int cacheSize,
            @DefaultValue("6h") Duration cacheTtl) {
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

    public Duration restanti(Duration usato) {
        Duration r = budget.minus(usato);
        return r.isNegative() ? Duration.ZERO : r;
    }
}
