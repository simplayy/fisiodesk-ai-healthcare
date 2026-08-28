package it.fisiodesk.assistant.query;

import java.util.List;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.micrometer.core.instrument.MeterRegistry;
import it.fisiodesk.assistant.clinical.Vocabolario;
import it.fisiodesk.assistant.config.AiModels;
import it.fisiodesk.assistant.config.AssistantProperties;

/**
 * Domanda -> piano, con tre livelli: cache (stessa domanda, stesso piano, zero costo), modello entro
 * un timeout, altrimenti regole. Se il modello sfora il timeout la risposta parte comunque a regole
 * e il piano del modello, quando arriva, finisce in cache per la volta successiva.
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

    public PlanService(RulePlanner regole, AiModels modelli, AssistantProperties props, MeterRegistry metrics) {
        this.regole = regole;
        this.modello = modelli.chatClient().map(LlmPlanner::new).orElse(null);
        this.cfg = props.planner();
        this.metrics = metrics;
        this.cache = Caffeine.newBuilder().maximumSize(cfg.cacheSize()).expireAfterWrite(cfg.cacheTtl()).build();
        // Coda corta e scarto: sotto carico le domande nuove vanno a regole invece di accodarsi al modello.
        this.executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(20), r -> Thread.ofVirtual().name("planner").unstarted(r),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public Planned pianifica(String domanda) {
        String chiave = Vocabolario.normalizza(domanda);
        QueryPlan inCache = cache.getIfPresent(chiave);
        if (inCache != null) {
            return conta(new Planned(inCache, "cache"));
        }
        if (modello == null) {
            return conta(new Planned(regole.pianifica(domanda).orElseThrow(), "regole"));
        }
        CompletableFuture<QueryPlan> futuro;
        try {
            futuro = CompletableFuture.supplyAsync(() -> modello.pianifica(domanda).orElse(null), executor);
        } catch (RejectedExecutionException e) {
            return conta(new Planned(regole.pianifica(domanda).orElseThrow(), "regole"));
        }
        futuro.thenAccept(p -> {
            if (p != null) {
                cache.put(chiave, p);
            }
        });
        try {
            QueryPlan p = futuro.get(cfg.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (p != null) {
                return conta(new Planned(p, "modello"));
            }
        } catch (TimeoutException e) {
            log.debug("Modello oltre {} per '{}', rispondo a regole", cfg.timeout(), domanda);
        } catch (ExecutionException | InterruptedException e) {
            log.warn("Pianificazione asincrona fallita: {}", e.getMessage());
        }
        return conta(new Planned(regole.pianifica(domanda).orElseThrow(), "regole"));
    }

    @EventListener(ApplicationReadyEvent.class)
    void riscalda() {
        List<String> domande = cfg.warmup();
        if (modello == null || domande.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("planner-warmup").start(() -> domande.forEach(d -> {
            QueryPlan p = modello.pianifica(d).orElse(null);
            if (p != null) {
                cache.put(Vocabolario.normalizza(d), p);
                log.info("Piano pre-calcolato per: {}", d);
            }
        }));
    }

    private Planned conta(Planned p) {
        metrics.counter("assistant.planner", "origine", p.origine()).increment();
        return p;
    }
}
