package it.fisiodesk.assistant.query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import it.fisiodesk.assistant.clinical.Andamento;
import it.fisiodesk.assistant.clinical.Regione;
import it.fisiodesk.assistant.config.AiModels;
import it.fisiodesk.assistant.config.AssistantProperties;
import it.fisiodesk.assistant.enrichment.VectorIndexManager;
import it.fisiodesk.assistant.query.QueryPlan.AndamentoRichiesto;
import it.fisiodesk.assistant.query.QueryPlan.Appuntamento;
import it.fisiodesk.assistant.query.ReferenceDates.Periodo;

/**
 * Esecuzione del piano: una sola aggregation su annotazioni_cliniche che filtra, raggruppa per
 * paziente, aggancia l'ultimo appuntamento e l'anagrafica. Nessuna chiamata al modello a query time.
 * <p>
 * Regione nota -> filtro esatto sui tag estratti dalle note (precisione clinica).
 * Regione fuori tassonomia -> $vectorSearch sul testo della condizione: le note più affini, ordinate.
 * È un ripiego dichiarato, non un filtro: gli embedding non separano regioni vicine (vedi docs).
 */
@Service
public class RetrievalService {

    public record Esito(List<SearchResult.Paziente> pazienti, String modalita) {
    }

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final MongoTemplate mongo;
    private final @Nullable EmbeddingModel embedding;
    private final VectorIndexManager indice;
    private final AssistantProperties.Retrieval cfg;
    /** L'embedding della condizione costa secondi su CPU: la stessa condizione non si ricalcola. */
    private final Cache<String, List<Double>> vettori = Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofHours(6)).build();
    private final Executor esecutore = r -> Thread.ofVirtual().name("embedding").start(r);
    /** Ultima latenza osservata del modello di embedding; 0 finché non se ne conosce una. */
    private volatile long ultimaLatenzaMs;

    public RetrievalService(MongoTemplate mongo, AiModels modelli, VectorIndexManager indice, AssistantProperties props) {
        this.mongo = mongo;
        this.embedding = modelli.embedding().orElse(null);
        this.indice = indice;
        this.cfg = props.retrieval();
    }

    public Esito cerca(QueryPlan piano, @Nullable ObjectId professionista, Periodo periodo, Duration entro) {
        Document filtro = new Document("data", new Document("$gte", Date.from(periodo.inizio())).append("$lte", Date.from(periodo.fine())));
        if (professionista != null) {
            filtro.append("professionista_id", professionista);
        }
        List<Document> pipeline = new ArrayList<>();
        List<Double> vettore = piano.condizioneLibera() && embedding != null && indice.pronto() ? vettore(piano.condizione(), entro) : null;
        boolean semantica = vettore != null;
        if (semantica) {
            if (piano.andamento() != AndamentoRichiesto.qualsiasi) {
                filtro.append("andamenti", piano.andamento().name());
            }
            pipeline.add(new Document("$vectorSearch", new Document("index", VectorIndexManager.INDEX)
                    .append("path", "embedding")
                    .append("queryVector", vettore)
                    .append("numCandidates", cfg.vectorLimit() * 4)
                    .append("limit", cfg.vectorLimit())
                    .append("filter", filtro)));
            pipeline.add(new Document("$addFields", new Document("punteggio", new Document("$meta", "vectorSearchScore"))));
            pipeline.add(new Document("$match", new Document("punteggio", new Document("$gte", cfg.similarityThreshold()))));
        } else {
            Document problema = new Document();
            if (piano.regioneNota()) {
                problema.append("regione", piano.regione().name());
            }
            if (piano.andamento() != AndamentoRichiesto.qualsiasi) {
                problema.append("andamento", piano.andamento().name());
            }
            if (!problema.isEmpty()) {
                filtro.append("problemi", new Document("$elemMatch", problema));
            }
            pipeline.add(new Document("$match", filtro));
        }
        pipeline.add(new Document("$sort", new Document("data", -1)));
        pipeline.add(new Document("$group", new Document("_id", "$paziente_id")
                .append("professionista_id", new Document("$first", "$professionista_id"))
                .append("ultima_nota", new Document("$first", "$data"))
                .append("punteggio", new Document("$max", "$punteggio"))
                .append("evidenze", new Document("$push", new Document("data", "$data").append("collezione", "$collezione")
                        .append("sintesi", "$sintesi").append("problemi", "$problemi").append("fonte", "$fonte").append("punteggio", "$punteggio")))));
        pipeline.add(new Document("$lookup", new Document("from", "eventi_calendario")
                .append("let", new Document("pid", "$_id"))
                .append("pipeline", List.of(
                        new Document("$match", new Document("$expr", new Document("$eq", List.of("$paziente_id", "$$pid")))
                                .append("data", new Document("$lte", Date.from(periodo.fine())))
                                .append("stato", new Document("$ne", "cancellato"))),
                        new Document("$sort", new Document("data", -1)),
                        new Document("$limit", 1)))
                .append("as", "ultimo_appuntamento")));
        pipeline.add(new Document("$unwind", new Document("path", "$ultimo_appuntamento").append("preserveNullAndEmptyArrays", true)));
        if (piano.appuntamento() == Appuntamento.ultimo_saltato) {
            pipeline.add(new Document("$match", new Document("ultimo_appuntamento.stato", "no_show")));
        }
        pipeline.add(new Document("$lookup", new Document("from", "pazienti").append("localField", "_id").append("foreignField", "_id").append("as", "paziente")));
        pipeline.add(new Document("$unwind", "$paziente"));
        pipeline.add(new Document("$sort", new Document("punteggio", -1).append("ultima_nota", -1)));

        List<SearchResult.Paziente> out = new ArrayList<>();
        mongo.getCollection(VectorIndexManager.COLLECTION).aggregate(pipeline).forEach(d -> out.add(mappa(d, piano)));
        return new Esito(out, semantica ? "semantica" : "strutturata");
    }

    /**
     * Embedding della condizione, ma non a costo di sforare il tempo di risposta: calcolarlo su CPU
     * costa secondi. Se non arriva in tempo si risponde senza il filtro di condizione (dicendolo) e
     * il calcolo prosegue, così la stessa domanda la volta dopo è immediata.
     */
    private @Nullable List<Double> vettore(String testo, Duration entro) {
        String chiave = testo.toLowerCase();
        List<Double> pronto = vettori.getIfPresent(chiave);
        if (pronto != null) {
            return pronto;
        }
        CompletableFuture<List<Double>> futuro = CompletableFuture.supplyAsync(() -> vettori.get(chiave, this::calcola), esecutore);
        long attesa = entro.toMillis();
        if (attesa <= 0 || (ultimaLatenzaMs > 0 && ultimaLatenzaMs > attesa)) {
            // Il modello di embedding è già risultato più lento del tempo disponibile: si risponde
            // senza filtro di condizione e il calcolo prosegue, così la stessa domanda poi è immediata.
            return null;
        }
        try {
            return futuro.get(attesa, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.debug("Embedding di '{}' oltre {}, rispondo senza filtro di condizione", testo, entro);
        } catch (ExecutionException | InterruptedException e) {
            log.warn("Embedding fallito: {}", e.getMessage());
        }
        return null;
    }

    private List<Double> calcola(String testo) {
        long inizio = System.nanoTime();
        float[] v = embedding.embed(testo);
        ultimaLatenzaMs = (System.nanoTime() - inizio) / 1_000_000;
        List<Double> out = new ArrayList<>(v.length);
        for (float f : v) {
            out.add((double) f);
        }
        return out;
    }

    private static SearchResult.Paziente mappa(Document d, QueryPlan piano) {
        Document p = d.get("paziente", Document.class);
        SearchResult.Anagrafica anagrafica = new SearchResult.Anagrafica(p.getObjectId("_id").toHexString(), p.getString("nome"), p.getString("cognome"),
                p.getInteger("eta"), p.getString("telefono"), p.getString("email"), p.getString("stato"));
        List<SearchResult.Evidenza> evidenze = d.getList("evidenze", Document.class).stream().map(e -> evidenza(e, piano)).toList();
        Document app = d.get("ultimo_appuntamento", Document.class);
        SearchResult.Appuntamento ultimo = app == null ? null
                : new SearchResult.Appuntamento(app.getDate("data").toInstant(), app.getString("stato"), app.getInteger("durata"), app.getString("note"));
        ObjectId prof = d.getObjectId("professionista_id");
        return new SearchResult.Paziente(anagrafica, prof == null ? null : prof.toHexString(), evidenze, ultimo, d.getDouble("punteggio"));
    }

    /** Della nota si mostra il problema che ha fatto scattare il match (o il primo, se la ricerca era libera). */
    private static SearchResult.Evidenza evidenza(Document e, QueryPlan piano) {
        List<Document> problemi = e.getList("problemi", Document.class);
        Optional<Document> scelto = problemi.stream()
                .filter(pr -> !piano.regioneNota() || piano.regione().name().equals(pr.getString("regione")))
                .filter(pr -> piano.andamento() == AndamentoRichiesto.qualsiasi || piano.andamento().name().equals(pr.getString("andamento")))
                .findFirst()
                .or(() -> problemi.stream().findFirst());
        Document pr = scelto.orElseGet(Document::new);
        return new SearchResult.Evidenza(e.getDate("data").toInstant(), e.getString("collezione"), e.getString("sintesi"),
                regione(pr.getString("regione")), pr.getString("condizione") == null ? "" : pr.getString("condizione"), andamento(pr.getString("andamento")),
                pr.getList("vas", Integer.class, List.of()), e.getString("fonte"), e.getDouble("punteggio"));
    }

    private static Regione regione(@Nullable String s) {
        try {
            return s == null ? Regione.altro : Regione.valueOf(s);
        } catch (IllegalArgumentException e) {
            log.debug("Regione sconosciuta in archivio: {}", s);
            return Regione.altro;
        }
    }

    private static Andamento andamento(@Nullable String s) {
        try {
            return s == null ? Andamento.non_determinabile : Andamento.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Andamento.non_determinabile;
        }
    }
}
