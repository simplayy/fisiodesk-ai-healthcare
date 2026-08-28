package it.fisiodesk.assistant.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import it.fisiodesk.assistant.clinical.Regione;
import it.fisiodesk.assistant.query.QueryPlan.AndamentoRichiesto;
import it.fisiodesk.assistant.query.QueryPlan.Appuntamento;

class RulePlannerTest {

    private final RulePlanner planner = new RulePlanner();

    @Test
    void domandaTarget() {
        QueryPlan p = planner.pianifica("Mostra pazienti con dolore lombare che hanno mostrato miglioramento negli ultimi 3 mesi ma hanno saltato l'ultimo appuntamento").orElseThrow();
        assertThat(p).isEqualTo(new QueryPlan("dolore lombare", Regione.lombare, AndamentoRichiesto.miglioramento, 3, Appuntamento.ultimo_saltato));
    }

    @Test
    void sinonimiDelDoloreLombare() {
        for (String d : new String[] { "pazienti con lombalgia", "chi ha mal di schiena", "low back pain", "colpo della strega", "rachialgia lombare" }) {
            assertThat(planner.pianifica(d).orElseThrow().regione()).as(d).isEqualTo(Regione.lombare);
        }
    }

    @Test
    void altreRegioniAndamentiEFinestre() {
        QueryPlan p = planner.pianifica("pazienti con cervicalgia peggiorata nell'ultimo mese").orElseThrow();
        assertThat(p).isEqualTo(new QueryPlan("cervicalgia", Regione.cervicale, AndamentoRichiesto.peggioramento, 1, Appuntamento.qualsiasi));

        QueryPlan anno = planner.pianifica("spalla congelata stazionaria nell'ultimo anno").orElseThrow();
        assertThat(anno.regione()).isEqualTo(Regione.spalla);
        assertThat(anno.andamento()).isEqualTo(AndamentoRichiesto.stazionario);
        assertThat(anno.finestraMesi()).isEqualTo(12);
    }

    @Test
    void laCondizioneSiFermaPrimaDelVerbo() {
        QueryPlan p = planner.pianifica("quali pazienti con dolore al collo sono peggiorati nell'ultimo anno?").orElseThrow();
        assertThat(p).isEqualTo(new QueryPlan("dolore al collo", Regione.cervicale, AndamentoRichiesto.peggioramento, 12, Appuntamento.qualsiasi));
    }

    @Test
    void domandaSenzaCondizione() {
        QueryPlan p = planner.pianifica("chi non si è presentato all'ultimo appuntamento?").orElseThrow();
        assertThat(p).isEqualTo(new QueryPlan("", Regione.altro, AndamentoRichiesto.qualsiasi, 0, Appuntamento.ultimo_saltato));
    }

    /** Il modello viene interpellato solo qui: sono le domande che il vocabolario non sa ricondurre a filtri. */
    @Test
    void condizioneFuoriTassonomiaVaAlModello() {
        assertThat(planner.pianifica("pazienti con tendinite in miglioramento")).isEmpty();
        assertThat(planner.pianifica("pazienti con fibromialgia")).isEmpty();
        // "cervicobrachialgia" non è nel dizionario parola per parola, ma contiene "cervic": le regole bastano
        assertThat(planner.pianifica("pazienti con cervicobrachialgia").orElseThrow().regione()).isEqualTo(Regione.cervicale);
        assertThat(planner.ripiego("pazienti con tendinite in miglioramento").condizione()).isEqualTo("tendinite");
    }

    @Test
    void negazioneDellAndamentoVaAlModello() {
        assertThat(planner.pianifica("pazienti che non hanno avuto miglioramenti alla schiena")).isEmpty();
        assertThat(planner.pianifica("chi non è migliorato con la lombalgia?")).isEmpty();
        // "non presentato" non è una negazione dell'andamento: è la formulazione normale dell'appuntamento saltato
        assertThat(planner.pianifica("chi non si è presentato all'ultima seduta?")).isPresent();
    }

    @Test
    void domandaIncomprensibileVaAlModello() {
        assertThat(planner.pianifica("come vanno le cose?")).isEmpty();
        assertThat(planner.pianifica("")).isEmpty();
    }
}
