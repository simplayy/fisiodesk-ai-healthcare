package it.fisiodesk.assistant.enrichment;

import java.time.Instant;
import java.util.List;

import org.bson.types.ObjectId;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import it.fisiodesk.assistant.clinical.ClinicalFacts;

/**
 * Risultato dell'arricchimento di una nota: dati strutturati + embedding, in una collection separata
 * così da non toccare lo schema delle collection esistenti. L'id è "collezione:documento_id", quindi
 * rielaborare una nota è un upsert.
 */
@Document("annotazioni_cliniche")
public record Annotation(
        @Id String id,
        @Field("collezione") String collezione,
        @Field("documento_id") ObjectId documentoId,
        @Field("paziente_id") ObjectId pazienteId,
        @Field("professionista_id") ObjectId professionistaId,
        @Field("data") Instant data,
        @Field("hash") String hash,
        @Field("problemi") List<ClinicalFacts.Problema> problemi,
        /** Denormalizzazioni di problemi[].regione / andamento: i pre-filtri di $vectorSearch non supportano $elemMatch. */
        @Field("regioni") List<String> regioni,
        @Field("andamenti") List<String> andamenti,
        @Field("sintesi") String sintesi,
        @Field("embedding") @Nullable List<Double> embedding,
        @Field("fonte") String fonte,
        @Field("modello") String modello,
        @Field("versione") int versione,
        @Field("aggiornato_il") Instant aggiornatoIl) {

    public static final String FONTE_LLM = "llm";
    public static final String FONTE_REGOLE = "regole";

    public boolean daModello() {
        return FONTE_LLM.equals(fonte);
    }

    public Annotation conEmbedding(@Nullable List<Double> nuovo) {
        return new Annotation(id, collezione, documentoId, pazienteId, professionistaId, data, hash, problemi, regioni, andamenti,
                sintesi, nuovo, fonte, modello, versione, Instant.now());
    }
}
