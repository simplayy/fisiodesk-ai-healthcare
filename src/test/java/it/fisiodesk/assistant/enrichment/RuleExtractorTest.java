package it.fisiodesk.assistant.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import it.fisiodesk.assistant.SeedData;
import it.fisiodesk.assistant.clinical.Andamento;
import it.fisiodesk.assistant.clinical.ClinicalFacts;
import it.fisiodesk.assistant.clinical.Regione;

/** Gold set costruito a mano sulle 32 note del dataset: regione e andamento attesi per ciascuna. */
class RuleExtractorTest {

    private final RuleExtractor extractor = new RuleExtractor();

    record Atteso(Regione regione, Andamento andamento) {
    }

    private static final Map<String, Atteso> GOLD = Map.ofEntries(
            Map.entry("schede:11:2024-11-15", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("schede:11:2024-12-01", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("schede:12:2024-10-20", new Atteso(Regione.lombare, Andamento.non_determinabile)),
            Map.entry("schede:12:2024-11-10", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("schede:12:2024-12-05", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("schede:13:2024-10-15", new Atteso(Regione.cervicale, Andamento.non_determinabile)),
            Map.entry("schede:13:2024-11-20", new Atteso(Regione.cervicale, Andamento.miglioramento)),
            Map.entry("schede:14:2024-09-10", new Atteso(Regione.lombare, Andamento.non_determinabile)),
            Map.entry("schede:14:2024-10-25", new Atteso(Regione.lombare, Andamento.stazionario)),
            Map.entry("schede:14:2024-12-10", new Atteso(Regione.lombare, Andamento.peggioramento)),
            Map.entry("schede:15:2024-09-15", new Atteso(Regione.lombare, Andamento.non_determinabile)),
            Map.entry("schede:15:2024-10-30", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("schede:15:2024-12-15", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("schede:16:2024-06-20", new Atteso(Regione.spalla, Andamento.non_determinabile)),
            Map.entry("schede:17:2024-09-25", new Atteso(Regione.lombare, Andamento.non_determinabile)),
            Map.entry("schede:17:2024-11-10", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("schede:17:2024-12-18", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("diario:11:2024-11-15", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("diario:11:2024-12-01", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("diario:12:2024-11-10", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("diario:13:2024-10-15", new Atteso(Regione.cervicale, Andamento.non_determinabile)),
            Map.entry("diario:13:2024-11-20", new Atteso(Regione.cervicale, Andamento.miglioramento)),
            Map.entry("diario:14:2024-09-10", new Atteso(Regione.lombare, Andamento.non_determinabile)),
            Map.entry("diario:14:2024-10-25", new Atteso(Regione.lombare, Andamento.peggioramento)),
            Map.entry("diario:14:2024-12-10", new Atteso(Regione.lombare, Andamento.peggioramento)),
            Map.entry("diario:15:2024-09-15", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("diario:15:2024-10-30", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("diario:15:2024-12-15", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("diario:16:2024-06-20", new Atteso(Regione.spalla, Andamento.non_determinabile)),
            Map.entry("diario:17:2024-09-25", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("diario:17:2024-11-10", new Atteso(Regione.lombare, Andamento.miglioramento)),
            Map.entry("diario:17:2024-12-18", new Atteso(Regione.lombare, Andamento.miglioramento)));

    @Test
    void tutteLeNoteDelDatasetRispettanoIlGoldSet() {
        int verificate = 0;
        for (String collezione : List.of("schede_valutazione", "diario_trattamenti")) {
            for (Document d : SeedData.leggi(collezione)) {
                String chiave = collezione.substring(0, 6) + ":" + d.getObjectId("paziente_id").toHexString().substring(22) + ":"
                        + d.getDate("data").toInstant().toString().substring(0, 10);
                Atteso atteso = GOLD.get(chiave);
                assertThat(atteso).as("manca il gold per " + chiave).isNotNull();
                ClinicalFacts facts = extractor.estrai(d.getString("descrizione")).orElseThrow();
                assertThat(facts.problemi()).as(chiave).hasSize(1);
                assertThat(facts.problemi().getFirst().regione()).as(chiave).isEqualTo(atteso.regione());
                assertThat(facts.problemi().getFirst().andamento()).as(chiave).isEqualTo(atteso.andamento());
                assertThat(facts.sintesi()).isNotBlank();
                verificate++;
            }
        }
        assertThat(verificate).isEqualTo(GOLD.size());
    }

    @Test
    void estraeIPunteggiVasInOrdine() {
        assertThat(RuleExtractor.vas("Scala del dolore da 8/10 a 3/10.")).containsExactly(8, 3);
        assertThat(RuleExtractor.vas("VAS 9/10, braccio oltre i 45°, mobilità +60%")).containsExactly(9);
        assertThat(RuleExtractor.vas("nessun punteggio")).isEmpty();
    }

    @Test
    void laNegazioneNonDiventaMiglioramento() {
        ClinicalFacts f = extractor.estrai("Il mal di schiena non migliora nonostante le terapie.").orElseThrow();
        assertThat(f.problemi().getFirst().andamento()).isEqualTo(Andamento.peggioramento);
    }

    @Test
    void testoSenzaRegioneFinisceInAltro() {
        ClinicalFacts f = extractor.estrai("Paziente riferisce vertigini al risveglio, in miglioramento.").orElseThrow();
        assertThat(f.problemi().getFirst().regione()).isEqualTo(Regione.altro);
        assertThat(f.problemi().getFirst().andamento()).isEqualTo(Andamento.miglioramento);
    }

    @Test
    void iVasNonPresentiNelTestoVengonoScartati() {
        ClinicalFacts dalModello = new ClinicalFacts(List.of(new ClinicalFacts.Problema(Regione.spalla, "spalla congelata", Andamento.non_determinabile, List.of(10))), "x");
        ClinicalFacts puliti = EnrichmentService.senzaVasInventati(dalModello, "Spalla congelata destra. Dolore notturno importante.");
        assertThat(puliti.problemi().getFirst().vas()).isEmpty();
        ClinicalFacts ok = EnrichmentService.senzaVasInventati(new ClinicalFacts(List.of(new ClinicalFacts.Problema(Regione.lombare, "lombalgia", Andamento.miglioramento, List.of(8, 3))), "x"),
                "Scala del dolore da 8/10 a 3/10.");
        assertThat(ok.problemi().getFirst().vas()).containsExactly(8, 3);
    }
}
