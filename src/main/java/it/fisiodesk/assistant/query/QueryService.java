package it.fisiodesk.assistant.query;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.bson.types.ObjectId;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import it.fisiodesk.assistant.config.AssistantProperties;
import it.fisiodesk.assistant.enrichment.EnrichmentService;
import it.fisiodesk.assistant.enrichment.EnrichmentStatus;

@Service
public class QueryService {

    /** Quota del budget lasciata all'aggregation e alla serializzazione della risposta. */
    private static final Duration RISERVA_QUERY = Duration.ofMillis(250);

    private final PlanService planner;
    private final RetrievalService retrieval;
    private final ReferenceDates date;
    private final EnrichmentService enrichment;
    private final AssistantProperties props;
    private final Timer tempoPiano;
    private final Timer tempoRicerca;

    public QueryService(PlanService planner, RetrievalService retrieval, ReferenceDates date, EnrichmentService enrichment,
            AssistantProperties props, MeterRegistry metrics) {
        this.planner = planner;
        this.retrieval = retrieval;
        this.date = date;
        this.enrichment = enrichment;
        this.props = props;
        this.tempoPiano = Timer.builder("assistant.query.plan").register(metrics);
        this.tempoRicerca = Timer.builder("assistant.query.retrieval").register(metrics);
    }

    public SearchResult rispondi(String domanda, @Nullable ObjectId professionista, @Nullable LocalDate dataRiferimento) {
        long t0 = System.nanoTime();
        PlanService.Planned planned = tempoPiano.record(() -> planner.pianifica(domanda));
        long t1 = System.nanoTime();
        LocalDate riferimento = date.riferimento(dataRiferimento);
        ReferenceDates.Periodo periodo = date.periodo(riferimento, planned.piano().finestraMesi());
        Duration restanti = props.restanti(Duration.ofNanos(t1 - t0)).minus(RISERVA_QUERY);
        RetrievalService.Esito esito = tempoRicerca.record(() -> retrieval.cerca(planned.piano(), professionista, periodo, restanti));
        long t2 = System.nanoTime();
        return new SearchResult(domanda, SearchResult.Piano.di(planned.piano(), planned.origine()), new SearchResult.Periodo(periodo.da(), periodo.a()),
                esito.modalita(), esito.pazienti(), new SearchResult.Tempi(ms(t1 - t0), ms(t2 - t1), ms(t2 - t0)), avvisi(planned, esito));
    }

    private List<String> avvisi(PlanService.Planned planned, RetrievalService.Esito esito) {
        List<String> avvisi = new ArrayList<>();
        EnrichmentStatus stato = enrichment.stato();
        if (!stato.completo()) {
            avvisi.add("Analisi delle note in corso: " + stato.daModello() + " su " + stato.note() + " note elaborate dal modello, le altre con regole. I risultati possono cambiare.");
        }
        // "regole" è il percorso normale: il vocabolario ha letto la domanda e il modello non serviva,
        // quindi non c'è niente da segnalare. Si avvisa solo quando la risposta è incompleta.
        String condizione = planned.piano().condizione();
        if ("semantica".equals(esito.modalita())) {
            avvisi.add("Condizione \"" + condizione + "\" fuori dal vocabolario clinico: risultati ordinati per affinità semantica, "
                    + "non filtrati per regione. Verifica le evidenze.");
        } else if (planned.piano().condizioneLibera()) {
            avvisi.add("Condizione \"" + condizione + "\" fuori dal vocabolario clinico: modello e ricerca semantica non pronti entro "
                    + "il tempo di risposta, il filtro sulla condizione non è stato applicato. Riprova fra qualche secondo.");
        } else if ("ripiego".equals(planned.origine())) {
            avvisi.add("Domanda fuori dal vocabolario clinico e modello non disponibile entro il tempo di risposta: "
                    + "sono stati applicati solo i filtri che le regole hanno saputo estrarre. Riprova fra qualche secondo.");
        }
        return avvisi;
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
