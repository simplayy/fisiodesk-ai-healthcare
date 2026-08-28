package it.fisiodesk.assistant.enrichment;

import org.jspecify.annotations.Nullable;

public record EnrichmentStatus(
        long note,
        long annotate,
        long daModello,
        long daRegole,
        long conEmbedding,
        boolean inCorso,
        boolean completo,
        @Nullable String modelloChat,
        @Nullable String modelloEmbedding,
        String indiceVettoriale) {
}
