package it.fisiodesk.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBAtlasLocalContainer;

import it.fisiodesk.assistant.enrichment.Annotation;
import it.fisiodesk.assistant.enrichment.AnnotationRepository;
import it.fisiodesk.assistant.enrichment.EnrichmentService;
import it.fisiodesk.assistant.query.SearchResult;

/**
 * End-to-end sul dataset ufficiale, senza modelli (solo regole): verifica che la query target
 * restituisca esattamente i quattro casi positivi e nessuno dei negativi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "assistant.enrichment.startup-delay=1h" })
@AutoConfigureTestRestTemplate
@Testcontainers
class AssistantApplicationTest {

    static final String DOMANDA = "Mostra pazienti con dolore lombare che hanno mostrato miglioramento negli ultimi 3 mesi ma hanno saltato l'ultimo appuntamento";
    static final String MARIO = "507f1f77bcf86cd799439011", LAURA = "507f1f77bcf86cd799439012", GIUSEPPE = "507f1f77bcf86cd799439013",
            ANNA = "507f1f77bcf86cd799439014", ROBERTO = "507f1f77bcf86cd799439015", FRANCESCA = "507f1f77bcf86cd799439016", MARCO = "507f1f77bcf86cd799439017";

    @Container
    @ServiceConnection
    static MongoDBAtlasLocalContainer mongo = new MongoDBAtlasLocalContainer("mongodb/mongodb-atlas-local:8.0");

    @Autowired
    MongoTemplate template;
    @Autowired
    EnrichmentService enrichment;
    @Autowired
    AnnotationRepository annotazioni;
    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void seed() {
        SeedData.carica(template);
        if (annotazioni.count() < 32) {
            enrichment.riconcilia();
        }
    }

    @Test
    void laQueryTargetTrovaSoloICasiPositivi() {
        SearchResult r = cerca(Map.of("domanda", DOMANDA));
        assertThat(ids(r)).containsExactlyInAnyOrder(MARIO, LAURA, ROBERTO, MARCO);
        assertThat(ids(r)).doesNotContain(ANNA, GIUSEPPE, FRANCESCA);
        assertThat(r.piano().origine()).isEqualTo("regole");
        assertThat(r.modalita()).isEqualTo("strutturata");
        // il vocabolario ha letto la domanda: è il percorso normale, non c'è niente da segnalare
        assertThat(r.avvisi()).isEmpty();
        assertThat(r.periodo().a()).hasToString("2024-12-31");
        assertThat(r.periodo().da()).hasToString("2024-09-30");
        for (SearchResult.Paziente p : r.risultati()) {
            assertThat(p.ultimoAppuntamento().stato()).isEqualTo("no_show");
            assertThat(p.evidenze()).isNotEmpty().allSatisfy(e -> {
                assertThat(e.regione().name()).isEqualTo("lombare");
                assertThat(e.andamento().name()).isEqualTo("miglioramento");
                assertThat(e.data()).isAfterOrEqualTo(Instant.parse("2024-09-30T00:00:00Z"));
            });
        }
        assertThat(r.tempi().totaleMs()).isLessThan(2000);
    }

    @Test
    void ogniProfessionistaVedeSoloISuoiPazienti() {
        assertThat(ids(cerca(Map.of("domanda", DOMANDA, "professionista_id", "507f1f77bcf86cd799439021")))).containsExactlyInAnyOrder(MARIO, LAURA, ROBERTO);
        assertThat(ids(cerca(Map.of("domanda", DOMANDA, "professionista_id", "507f1f77bcf86cd799439022")))).isEmpty();
        assertThat(ids(cerca(Map.of("domanda", DOMANDA, "professionista_id", "507f1f77bcf86cd799439023")))).containsExactly(MARCO);
    }

    @Test
    void laDataDiRiferimentoCambiaLUltimoAppuntamento() {
        // il 30/12 l'ultimo appuntamento di Colombo è ancora quello completato del 18/12
        assertThat(ids(cerca(Map.of("domanda", DOMANDA, "data_riferimento", "2024-12-30")))).containsExactlyInAnyOrder(MARIO, LAURA, ROBERTO);
    }

    @Test
    void altreDomandeDellaStessaFamiglia() {
        SearchResult cervicale = cerca(Map.of("domanda", "pazienti con cervicalgia migliorati negli ultimi 3 mesi"));
        assertThat(ids(cervicale)).containsExactly(GIUSEPPE);
        assertThat(cervicale.risultati().getFirst().ultimoAppuntamento().stato()).isEqualTo("prenotato");

        SearchResult peggiorati = cerca(Map.of("domanda", "chi ha il mal di schiena peggiorato nell'ultimo mese?"));
        assertThat(ids(peggiorati)).containsExactly(ANNA);

        SearchResult saltati = cerca(Map.of("domanda", "chi ha saltato l'ultimo appuntamento?"));
        assertThat(ids(saltati)).containsExactlyInAnyOrder(MARIO, LAURA, ANNA, ROBERTO, MARCO);
    }

    @Test
    void storiaDelPaziente() {
        ResponseEntity<Map> r = rest.getForEntity("/api/pazienti/" + MARIO + "/storia", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) r.getBody().get("note")).hasSize(4);
        assertThat((List<?>) r.getBody().get("eventi")).hasSize(3);
        assertThat(rest.getForEntity("/api/pazienti/507f1f77bcf86cd799439099/storia", Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void statoEValidazione() {
        ResponseEntity<Map> stato = rest.getForEntity("/api/annotazioni/stato", Map.class);
        assertThat(stato.getBody().get("note")).isEqualTo(32);
        assertThat(stato.getBody().get("annotate")).isEqualTo(32);
        assertThat(stato.getBody().get("completo")).isEqualTo(true);

        ResponseEntity<Map> vuota = rest.postForEntity("/api/ricerca", Map.of("domanda", " "), Map.class);
        assertThat(vuota.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> idErrato = rest.postForEntity("/api/ricerca", Map.of("domanda", DOMANDA, "professionista_id", "xyz"), Map.class);
        assertThat(idErrato.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/actuator/health", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unaNotaNuovaVieneAnnotataDalChangeStream() throws InterruptedException {
        ObjectId id = new ObjectId();
        template.getCollection("schede_valutazione").insertOne(new Document("_id", id)
                .append("paziente_id", new ObjectId(GIUSEPPE)).append("professionista_id", new ObjectId("507f1f77bcf86cd799439021"))
                .append("data", Date.from(Instant.parse("2024-12-29T09:00:00Z")))
                .append("descrizione", "Comparsa di lombalgia acuta dopo sforzo, VAS 6/10. Prima valutazione della zona lombare."));
        Annotation a = null;
        for (int i = 0; i < 40 && a == null; i++) {
            Thread.sleep(250);
            a = annotazioni.findById("schede_valutazione:" + id.toHexString()).orElse(null);
        }
        assertThat(a).isNotNull();
        assertThat(a.regioni()).containsExactly("lombare");
        assertThat(a.fonte()).isEqualTo("regole");
        template.getCollection("schede_valutazione").deleteOne(new Document("_id", id));
        annotazioni.deleteById("schede_valutazione:" + id.toHexString());
    }

    private SearchResult cerca(Map<String, String> body) {
        ResponseEntity<SearchResult> r = rest.postForEntity("/api/ricerca", body, SearchResult.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private static Set<String> ids(SearchResult r) {
        return r.risultati().stream().map(p -> p.paziente().id()).collect(Collectors.toSet());
    }
}
