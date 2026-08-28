package it.fisiodesk.assistant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.bson.BsonArray;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Carica in Mongo gli stessi file JSON (Extended JSON) che il compose importa con mongoimport. */
public final class SeedData {

    public static final List<String> COLLEZIONI = List.of("pazienti", "schede_valutazione", "diario_trattamenti", "eventi_calendario");

    private SeedData() {
    }

    public static void carica(MongoTemplate mongo) {
        for (String c : COLLEZIONI) {
            if (mongo.getCollection(c).countDocuments() == 0) {
                mongo.getCollection(c).insertMany(leggi(c));
            }
        }
    }

    public static List<Document> leggi(String collezione) {
        try {
            String json = Files.readString(Path.of("data", collezione + ".json"));
            return BsonArray.parse(json).getValues().stream().map(v -> Document.parse(v.asDocument().toJson())).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Dati di test non trovati per " + collezione, e);
        }
    }
}
