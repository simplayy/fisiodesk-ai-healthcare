package it.fisiodesk.assistant.enrichment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import it.fisiodesk.assistant.clinical.ClinicalFacts;
import it.fisiodesk.assistant.config.AiModels;
import it.fisiodesk.assistant.config.AssistantProperties;

/**
 * Pipeline di arricchimento delle note. Due passate: la prima, a regole, è istantanea e rende
 * il sistema interrogabile subito; la seconda, con il modello, sostituisce le annotazioni a regole
 * man mano che il modello risponde. Le note già elaborate (stesso hash, stessa versione del prompt)
 * non vengono mai rimandate al modello: è qui che si tiene sotto controllo il costo delle API.
 */
@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);
    private static final int MAX_ERRORI_CONSECUTIVI = 3;

    private final NoteRepository note;
    private final AnnotationRepository annotazioni;
    private final RuleExtractor regole;
    private final @Nullable LlmExtractor modello;
    private final @Nullable EmbeddingModel embedding;
    private final VectorIndexManager indice;
    private final AssistantProperties props;
    private final AiModels modelli;
    private final AtomicBoolean inCorso = new AtomicBoolean();
    private final Timer tempoModello;
    private final Counter chiamateModello;
    private final Counter erroriModello;

    public EnrichmentService(NoteRepository note, AnnotationRepository annotazioni, RuleExtractor regole, AiModels modelli,
            VectorIndexManager indice, AssistantProperties props, MeterRegistry metrics) {
        this.note = note;
        this.annotazioni = annotazioni;
        this.regole = regole;
        this.modelli = modelli;
        this.modello = modelli.extractor().orElse(null);
        this.embedding = modelli.embedding().orElse(null);
        this.indice = indice;
        this.props = props;
        this.tempoModello = Timer.builder("assistant.enrichment.llm").description("Tempo di estrazione con il modello").register(metrics);
        this.chiamateModello = metrics.counter("assistant.enrichment.llm.calls", "outcome", "ok");
        this.erroriModello = metrics.counter("assistant.enrichment.llm.calls", "outcome", "error");
    }

    @Scheduled(initialDelayString = "${assistant.enrichment.startup-delay:30s}", fixedDelayString = "${assistant.enrichment.reconcile-interval:60s}")
    public void riconcilia() {
        if (!props.enrichment().enabled() || !inCorso.compareAndSet(false, true)) {
            return;
        }
        try {
            int nuove = passataRegole();
            int migliorate = passataModello();
            if (nuove + migliorate > 0) {
                log.info("Riconciliazione: {} note annotate a regole, {} rielaborate dal modello", nuove, migliorate);
            }
        } catch (RuntimeException e) {
            log.error("Riconciliazione interrotta", e);
        } finally {
            inCorso.set(false);
        }
    }

    /** Note nuove o modificate: annotazione immediata a regole, così la query non aspetta il modello. */
    private int passataRegole() {
        int n = 0;
        try (Stream<SourceNote> tutte = note.tutte()) {
            for (SourceNote nota : (Iterable<SourceNote>) tutte::iterator) {
                Optional<Annotation> esistente = annotazioni.findById(nota.annotationId());
                if (esistente.isEmpty() || !esistente.get().hash().equals(hash(nota.testo()))) {
                    salva(nota, regole.estrai(nota.testo()).orElseThrow(), regole.nome(), Annotation.FONTE_REGOLE, esistente.map(Annotation::embedding).orElse(null));
                    n++;
                }
            }
        }
        return n;
    }

    /** Annotazioni ancora a regole o senza embedding: le passa al modello finché risponde. */
    private int passataModello() {
        if (modello == null && embedding == null) {
            return 0;
        }
        int n = 0;
        int erroriConsecutivi = 0;
        try (Stream<SourceNote> tutte = note.tutte()) {
            for (SourceNote nota : (Iterable<SourceNote>) tutte::iterator) {
                Annotation a = annotazioni.findById(nota.annotationId()).orElse(null);
                boolean serveModello = modello != null && (a == null || !a.daModello() || a.versione() != LlmExtractor.PROMPT_VERSION);
                boolean serveEmbedding = embedding != null && (a == null || a.embedding() == null);
                if (!serveModello && !serveEmbedding) {
                    continue;
                }
                try {
                    if (serveModello) {
                        elabora(nota, true);
                    } else {
                        annotazioni.save(a.conEmbedding(embed(nota.testo())));
                    }
                    n++;
                    erroriConsecutivi = 0;
                } catch (ModelUnavailableException e) {
                    erroriConsecutivi++;
                    if (erroriConsecutivi == 1) {
                        log.warn("Modello non disponibile, riprovo al prossimo giro: {}", e.getMessage());
                    }
                    if (erroriConsecutivi >= MAX_ERRORI_CONSECUTIVI) {
                        break;
                    }
                }
            }
        }
        return n;
    }

    /** Arricchimento completo di una singola nota (usato dal change stream e dalle riconciliazioni). */
    public Annotation elabora(SourceNote nota, boolean usaModello) {
        ClinicalFacts facts = null;
        String fonte = Annotation.FONTE_REGOLE;
        String nome = regole.nome();
        if (usaModello && modello != null) {
            Timer.Sample sample = Timer.start();
            try {
                facts = modello.estrai(nota.testo()).orElse(null);
                chiamateModello.increment();
            } catch (ModelUnavailableException e) {
                erroriModello.increment();
                throw e;
            } finally {
                sample.stop(tempoModello);
            }
            if (facts != null) {
                fonte = Annotation.FONTE_LLM;
                nome = modello.nome();
            }
        }
        if (facts == null) {
            facts = regole.estrai(nota.testo()).orElseThrow();
        }
        List<Double> vettore = null;
        if (embedding != null) {
            try {
                vettore = embed(nota.testo());
            } catch (ModelUnavailableException e) {
                if (!usaModello) {
                    log.debug("Embedding non disponibile per {}: {}", nota.annotationId(), e.getMessage());
                } else {
                    throw e;
                }
            }
        }
        return salva(nota, facts, nome, fonte, vettore);
    }

    private List<Double> embed(String testo) {
        try {
            float[] v = embedding.embed(testo);
            indice.ensure(v.length);
            List<Double> out = new ArrayList<>(v.length);
            for (float f : v) {
                out.add((double) f);
            }
            return out;
        } catch (RuntimeException e) {
            throw new ModelUnavailableException("Embedding fallito: " + e.getMessage(), e);
        }
    }

    private Annotation salva(SourceNote nota, ClinicalFacts facts, String nomeModello, String fonte, @Nullable List<Double> vettore) {
        facts = senzaVasInventati(facts, nota.testo());
        List<String> regioni = facts.problemi().stream().map(p -> p.regione().name()).distinct().toList();
        List<String> andamenti = facts.problemi().stream().map(p -> p.andamento().name()).distinct().toList();
        Annotation a = new Annotation(nota.annotationId(), nota.collezione(), nota.id(), nota.pazienteId(), nota.professionistaId(),
                nota.data(), hash(nota.testo()), facts.problemi(), regioni, andamenti, facts.sintesi(), vettore, fonte, nomeModello,
                LlmExtractor.PROMPT_VERSION, Instant.now());
        return annotazioni.save(a);
    }

    /** I modelli piccoli ogni tanto "completano" un punteggio VAS assente: si tengono solo i valori scritti nella nota. */
    static ClinicalFacts senzaVasInventati(ClinicalFacts facts, String testo) {
        List<Integer> nelTesto = RuleExtractor.vas(testo);
        List<ClinicalFacts.Problema> puliti = facts.problemi().stream()
                .map(p -> new ClinicalFacts.Problema(p.regione(), p.condizione(), p.andamento(), p.vas().stream().filter(nelTesto::contains).toList()))
                .toList();
        return new ClinicalFacts(puliti, facts.sintesi());
    }

    public EnrichmentStatus stato() {
        long totali = note.conta();
        long annotate = annotazioni.count();
        long llm = annotazioni.countByFonte(Annotation.FONTE_LLM);
        long senzaEmbedding = annotazioni.countByEmbeddingIsNull();
        boolean completo = annotate >= totali
                && (modello == null || llm >= totali)
                && (embedding == null || senzaEmbedding == 0);
        return new EnrichmentStatus(totali, annotate, llm, annotate - llm, annotate - senzaEmbedding, inCorso.get(), completo,
                modelli.nomeChat().orElse(null), modelli.nomeEmbedding().orElse(null), indice.stato().name());
    }

    public void azzera() {
        annotazioni.deleteAll();
    }

    static String hash(String testo) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(testo.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
