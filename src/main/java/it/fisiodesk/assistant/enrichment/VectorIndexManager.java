package it.fisiodesk.assistant.enrichment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.SearchIndexStatus;
import org.springframework.data.mongodb.core.index.VectorIndex;
import org.springframework.data.mongodb.core.index.VectorIndex.SimilarityFunction;
import org.springframework.stereotype.Component;

/**
 * Crea (una volta sola, alla prima embedding riuscita) l'indice Atlas Vector Search sulle annotazioni
 * e ne tiene d'occhio lo stato. Se il server non supporta la ricerca vettoriale la modalità semantica
 * resta semplicemente spenta: la query strutturata non ne ha bisogno.
 */
@Component
public class VectorIndexManager {

    public static final String COLLECTION = "annotazioni_cliniche";
    public static final String INDEX = "annotazioni_embedding";
    public static final List<String> FILTRI = List.of("professionista_id", "data", "regioni", "andamenti");

    private static final Logger log = LoggerFactory.getLogger(VectorIndexManager.class);
    private static final Duration CACHE = Duration.ofSeconds(15);

    private final MongoTemplate mongo;
    private volatile SearchIndexStatus stato = SearchIndexStatus.DOES_NOT_EXIST;
    private volatile Instant ultimoControllo = Instant.EPOCH;
    private volatile boolean nonSupportato;

    public VectorIndexManager(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void ensure(int dimensioni) {
        if (nonSupportato || stato() != SearchIndexStatus.DOES_NOT_EXIST) {
            return;
        }
        try {
            VectorIndex def = new VectorIndex(INDEX)
                    .addVector("embedding", v -> v.dimensions(dimensioni).similarity(SimilarityFunction.COSINE));
            FILTRI.forEach(def::addFilter);
            mongo.searchIndexOps(COLLECTION).createIndex(def);
            ultimoControllo = Instant.EPOCH;
            log.info("Indice vettoriale {} creato ({} dimensioni), in costruzione", INDEX, dimensioni);
        } catch (RuntimeException e) {
            nonSupportato = true;
            log.warn("Ricerca vettoriale non disponibile su questo MongoDB ({}). La ricerca semantica resta disattivata.", e.getMessage());
        }
    }

    public boolean pronto() {
        return stato() == SearchIndexStatus.READY;
    }

    public SearchIndexStatus stato() {
        if (nonSupportato) {
            return SearchIndexStatus.DOES_NOT_EXIST;
        }
        Instant ora = Instant.now();
        if (stato != SearchIndexStatus.READY && ultimoControllo.plus(CACHE).isBefore(ora)) {
            try {
                stato = mongo.searchIndexOps(COLLECTION).status(INDEX);
            } catch (RuntimeException e) {
                nonSupportato = true;
                log.warn("Impossibile leggere lo stato dell'indice vettoriale: {}", e.getMessage());
            }
            ultimoControllo = ora;
        }
        return stato;
    }
}
