package it.fisiodesk.assistant.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import it.fisiodesk.assistant.enrichment.VectorIndexManager;

/**
 * Indici classici per la query strutturata. L'unico che tocca una collection esistente è quello su
 * eventi_calendario (paziente_id, data), necessario per trovare l'ultimo appuntamento in O(1).
 */
@Component
public class MongoIndexes {

    private final MongoTemplate mongo;

    public MongoIndexes(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void crea() {
        var annotazioni = mongo.indexOps(VectorIndexManager.COLLECTION);
        annotazioni.createIndex(new Index().on("paziente_id", Sort.Direction.ASC).on("data", Sort.Direction.DESC));
        annotazioni.createIndex(new Index().on("professionista_id", Sort.Direction.ASC).on("data", Sort.Direction.DESC));
        annotazioni.createIndex(new Index().on("problemi.regione", Sort.Direction.ASC).on("problemi.andamento", Sort.Direction.ASC).on("data", Sort.Direction.DESC));
        mongo.indexOps("eventi_calendario").createIndex(new Index().on("paziente_id", Sort.Direction.ASC).on("data", Sort.Direction.DESC));
    }
}
