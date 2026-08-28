package it.fisiodesk.assistant.api;

import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fisiodesk.assistant.clinical.ClinicalFacts;
import it.fisiodesk.assistant.enrichment.Annotation;
import it.fisiodesk.assistant.enrichment.AnnotationRepository;
import it.fisiodesk.assistant.enrichment.NoteRepository;
import it.fisiodesk.assistant.enrichment.SourceNote;

/** Storia clinica di un paziente: note originali affiancate all'annotazione, più il calendario. */
@RestController
@RequestMapping("/api/pazienti")
public class PatientController {

    public record Storia(Map<String, Object> paziente, List<Nota> note, List<Evento> eventi) {
    }

    public record Nota(String id, String collezione, Instant data, String testo, @Nullable String sintesi, List<ClinicalFacts.Problema> problemi,
            @Nullable String fonte, @Nullable String modello) {
    }

    public record Evento(Instant data, String stato, @Nullable Integer durata, @Nullable String note) {
    }

    private final MongoTemplate mongo;
    private final NoteRepository note;
    private final AnnotationRepository annotazioni;

    public PatientController(MongoTemplate mongo, NoteRepository note, AnnotationRepository annotazioni) {
        this.mongo = mongo;
        this.note = note;
        this.annotazioni = annotazioni;
    }

    @GetMapping("/{id}/storia")
    public Storia storia(@PathVariable String id) {
        ObjectId oid = Ids.objectId(id, "id");
        Document paziente = mongo.findById(oid, Document.class, "pazienti");
        if (paziente == null) {
            throw new NotFoundException("Paziente " + id + " non trovato");
        }
        Map<String, Annotation> perNota = annotazioni.findByPazienteIdOrderByDataDesc(oid).stream()
                .collect(java.util.stream.Collectors.toMap(Annotation::id, a -> a));
        List<Nota> note = this.note.delPaziente(oid).stream()
                .sorted(Comparator.comparing(SourceNote::data).reversed())
                .map(n -> {
                    Annotation a = perNota.get(n.annotationId());
                    return new Nota(n.id().toHexString(), n.collezione(), n.data(), n.testo(), a == null ? null : a.sintesi(),
                            a == null ? List.of() : a.problemi(), a == null ? null : a.fonte(), a == null ? null : a.modello());
                })
                .toList();
        List<Evento> eventi = mongo.find(Query.query(Criteria.where("paziente_id").is(oid)).with(Sort.by(Sort.Direction.DESC, "data")), Document.class, "eventi_calendario")
                .stream()
                .map(e -> new Evento(e.getDate("data").toInstant(), e.getString("stato"), e.getInteger("durata"), e.getString("note")))
                .toList();
        Map<String, Object> anagrafica = new java.util.LinkedHashMap<>();
        paziente.forEach((k, v) -> anagrafica.put(k, v instanceof ObjectId o ? o.toHexString() : v instanceof Date d ? d.toInstant() : v));
        anagrafica.put("id", oid.toHexString());
        anagrafica.remove("_id");
        return new Storia(anagrafica, note, Objects.requireNonNullElse(eventi, List.of()));
    }
}
