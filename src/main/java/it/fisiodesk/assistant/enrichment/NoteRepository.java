package it.fisiodesk.assistant.enrichment;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class NoteRepository {

    static final List<String> COLLEZIONI = List.of(SourceNote.SCHEDE, SourceNote.DIARIO);

    private final MongoTemplate mongo;

    public NoteRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Stream<SourceNote> tutte() {
        return COLLEZIONI.stream().flatMap(c -> mongo.stream(new Query(), Document.class, c).map(d -> SourceNote.from(c, d)));
    }

    public Optional<SourceNote> trova(String collezione, ObjectId id) {
        if (!COLLEZIONI.contains(collezione)) {
            return Optional.empty();
        }
        Document d = mongo.findById(id, Document.class, collezione);
        return Optional.ofNullable(d).map(doc -> SourceNote.from(collezione, doc));
    }

    public List<SourceNote> delPaziente(ObjectId pazienteId) {
        Query q = Query.query(Criteria.where("paziente_id").is(pazienteId));
        return COLLEZIONI.stream().flatMap(c -> mongo.find(q, Document.class, c).stream().map(d -> SourceNote.from(c, d))).toList();
    }

    public long conta() {
        return COLLEZIONI.stream().mapToLong(c -> mongo.count(new Query(), c)).sum();
    }
}
