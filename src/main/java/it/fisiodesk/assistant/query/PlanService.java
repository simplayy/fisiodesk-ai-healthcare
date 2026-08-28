package it.fisiodesk.assistant.query;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.MeterRegistry;
import it.fisiodesk.assistant.clinical.Vocabolario;
import it.fisiodesk.assistant.config.AiModels;
import it.fisiodesk.assistant.config.AssistantProperties;

/**
 * Domanda -> piano di ricerca.
 * <p>
 * Il vocabolario prova per primo: quando riconduce la domanda a filtri concreti il piano è pronto
 * in microsecondi e il modello non viene nemmeno interpellato. Il modello serve per le domande che
 * il vocabolario non copre — condizioni fuori tassonomia, negazioni, formulazioni inattese — ed è
 * lì che si spendono tempo e token.
 * <p>
 * Quanto aspettare il modello non è una costante: si misura. Se le risposte precedenti sono
 * arrivate entro il timeout lo si aspetta, altrimenti si risponde subito con quanto estratto dalle
 * regole e lo si lascia finire in background — inutile bruciare un secondo e mezzo per un provider
 * che sappiamo già essere più lento. In entrambi i casi il piano del modello, quando arriva, resta
 * in cache per le volte successive.
 */
@Service
public class PlanService {

    public record Planned(QueryPlan piano, String origine) {
    }

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final RulePlanner regole;
    private final @Nullable LlmPlanner modello;
    private final AssistantProperties.Planner cfg;
    private final Cache<String, QueryPlan> cache;
    private final ThreadPoolExecutor executor;
    private final MeterRegistry metrics;
    /** Ultima latenza osservata del modello; 0 finché non se ne conosce una. */
    private volatile long ultimaLatenzaMs;

    public PlanService(RulePlanner regole, AiModels modelli, AssistantProperties props, MeterRegistry metrics) {
        this.regole = regole;
        this.modello = modelli.chatClient().map(LlmPlanner::new).orElse(null);
        this.cfg = props.planner();
        this.metrics = metrics;
        this.cache = Caffeine.newBuilder().maximumSize(cfg.cacheSize()).expireAfterWrite(cfg.cacheTtl()).build();
        // Coda corta e scarto: sotto carico le domande nuove vanno a regole invece di accodarsi al modello.
        this.executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(20),
                r -> Thread.ofVirtual().name("planner").unstarted(r), new ThreadPoolExecutor.AbortPolicy());
    }

    public Planned pianifica(String domanda) {
        Planned daRegole = regole.pianifica(domanda).map(p -> new Planned(p, "regole")).orElse(null);
        if (daRegole != null) {
            return conta(daRegole);
        }
        return conta(chiediAlModello(domanda));
    }

    private Planned chiediAlModello(String domanda) {
        QueryPlan ripiego = regole.ripiego(domanda);
        if (modello == null) {
            return new Planned(ripiego, "ripiego");
        }
        String chiave = Vocabolario.normalizza(domanda);
        QueryPlan inCache = cache.getIfPresent(chiave);
        if (inCache != null) {
            return new Planned(inCache, "cache");
        }
        CompletableFuture<QueryPlan> futuro;
        try {
            futuro = CompletableFuture.supplyAsync(() -> misura(domanda), executor);
        } catch (RejectedExecutionException e) {
            return new Planned(ripiego, "ripiego");
        }
        futuro.thenAccept(p -> {
            if (p != null) {
                cache.put(chiave, p);
            }
        });
        if (!valeLaPenaAspettare()) {
            return new Planned(ripiego, "ripiego");
        }
        try {
            QueryPlan p = futuro.get(cfg.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (p != null) {
                return new Planned(p, "modello");
            }
        } catch (TimeoutException e) {
            log.debug("Modello oltre {} per '{}', rispondo con quanto estratto dalle regole", cfg.timeout(), domanda);
        } catch (ExecutionException | InterruptedException e) {
            log.warn("Pianificazione asincrona fallita: {}", e.getMessage());
        }
        return new Planned(ripiego, "ripiego");
    }

    private boolean valeLaPenaAspettare() {
        return ultimaLatenzaMs == 0 || ultimaLatenzaMs <= cfg.timeout().toMillis();
    }

    private @Nullable QueryPlan misura(String domanda) {
        long inizio = System.nanoTime();
        QueryPlan p = modello.pianifica(domanda).orElse(null);
        long durata = (System.nanoTime() - inizio) / 1_000_000;
        if (ultimaLatenzaMs <= cfg.timeout().toMillis() && durata > cfg.timeout().toMillis()) {
            log.info("Il modello impiega {} ms per interpretare una domanda, oltre il limite di {}: "
                    + "d'ora in poi le domande fuori vocabolario ricevono subito il piano a regole e il modello lavora in background",
                    durata, cfg.timeout());
        }
        ultimaLatenzaMs = durata;
        return p;
    }

    private Planned conta(Planned p) {
        metrics.counter("assistant.planner", "origine", p.origine()).increment();
        return p;
    }
}
