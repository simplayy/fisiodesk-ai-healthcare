package it.fisiodesk.assistant.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import it.fisiodesk.assistant.enrichment.EnrichmentService;
import it.fisiodesk.assistant.enrichment.EnrichmentStatus;

@RestController
@RequestMapping("/api/annotazioni")
public class AnnotationController {

    private final EnrichmentService enrichment;

    public AnnotationController(EnrichmentService enrichment) {
        this.enrichment = enrichment;
    }

    @GetMapping("/stato")
    public EnrichmentStatus stato() {
        return enrichment.stato();
    }

    /** Forza una riconciliazione; con daCapo=true butta via tutte le annotazioni e ricomincia. */
    @PostMapping("/riprocessa")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void riprocessa(@RequestParam(defaultValue = "false") boolean daCapo) {
        if (daCapo) {
            enrichment.azzera();
        }
        Thread.ofVirtual().name("riconcilia-manuale").start(enrichment::riconcilia);
    }
}
