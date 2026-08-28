package it.fisiodesk.assistant.api;

import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** I professionisti presenti nei dati (non esiste una collection dedicata nel dataset). */
@RestController
public class ProfessionalController {

    public record Professionista(String id, long pazienti) {
    }

    private final MongoTemplate mongo;

    public ProfessionalController(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @GetMapping("/api/professionisti")
    public List<Professionista> elenco() {
        List<Document> pipeline = List.of(
                new Document("$group", new Document("_id", "$professionista_principale").append("pazienti", new Document("$sum", 1))),
                new Document("$sort", new Document("_id", 1)));
        List<Professionista> out = new java.util.ArrayList<>();
        mongo.getCollection("pazienti").aggregate(pipeline).forEach(d -> {
            ObjectId id = d.getObjectId("_id");
            if (id != null) {
                out.add(new Professionista(id.toHexString(), d.getInteger("pazienti")));
            }
        });
        return out;
    }
}
