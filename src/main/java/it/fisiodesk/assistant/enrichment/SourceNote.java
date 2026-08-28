package it.fisiodesk.assistant.enrichment;

import java.time.Instant;
import java.util.Date;

import org.bson.Document;
import org.bson.types.ObjectId;

/** Una nota testuale così com'è nelle collection esistenti (schede_valutazione, diario_trattamenti). */
public record SourceNote(String collezione, ObjectId id, ObjectId pazienteId, ObjectId professionistaId, Instant data, String testo) {

    public static final String SCHEDE = "schede_valutazione";
    public static final String DIARIO = "diario_trattamenti";

    public static SourceNote from(String collezione, Document d) {
        Date data = d.getDate("data");
        return new SourceNote(collezione, d.getObjectId("_id"), d.getObjectId("paziente_id"), d.getObjectId("professionista_id"),
                data == null ? Instant.EPOCH : data.toInstant(), d.getString("descrizione") == null ? "" : d.getString("descrizione"));
    }

    public String annotationId() {
        return collezione + ":" + id.toHexString();
    }
}
