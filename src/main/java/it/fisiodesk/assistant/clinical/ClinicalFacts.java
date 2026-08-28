package it.fisiodesk.assistant.clinical;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Ciò che estraiamo da una nota clinica. È anche lo schema JSON imposto al modello,
 * quindi niente campi opzionali: le liste vuote fanno da "assente".
 */
public record ClinicalFacts(
        @JsonPropertyDescription("Un elemento per ogni regione anatomica di cui la nota parla") List<Problema> problemi,
        @JsonPropertyDescription("Una frase, massimo 20 parole, che riassume la nota per il professionista") String sintesi) {

    public record Problema(
            @JsonPropertyDescription("Regione anatomica interessata") Regione regione,
            @JsonPropertyDescription("Nome breve e normalizzato della condizione, es. lombalgia, cervicalgia, spalla congelata") String condizione,
            @JsonPropertyDescription("Evoluzione della condizione secondo la nota") Andamento andamento,
            @JsonPropertyDescription("Punteggi del dolore 0-10 citati nella nota, in ordine cronologico (es. [8, 3]); vuoto se assenti") List<Integer> vas) {

        public Problema {
            vas = vas == null ? List.of() : List.copyOf(vas);
        }
    }

    public ClinicalFacts {
        problemi = problemi == null ? List.of() : List.copyOf(problemi);
    }

    public boolean isEmpty() {
        return problemi.isEmpty();
    }
}
