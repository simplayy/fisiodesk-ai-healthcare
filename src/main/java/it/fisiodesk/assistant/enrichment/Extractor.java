package it.fisiodesk.assistant.enrichment;

import java.util.Optional;

import it.fisiodesk.assistant.clinical.ClinicalFacts;

public interface Extractor {

    /** Vuoto se l'estrazione non è possibile (modello non raggiungibile, risposta non valida...). */
    Optional<ClinicalFacts> estrai(String testo);

    String nome();
}
