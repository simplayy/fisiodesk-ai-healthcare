package it.fisiodesk.assistant.query;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import it.fisiodesk.assistant.clinical.Regione;

/**
 * La domanda in linguaggio naturale ridotta a cinque campi. È l'unico contratto fra il modello
 * (o il parser a regole) e l'esecuzione su MongoDB: tutto ciò che il piano non esprime, il sistema
 * non lo cerca, e questo lo rende verificabile.
 */
public record QueryPlan(
        @JsonPropertyDescription("La condizione clinica cercata, con le parole della domanda (es. 'dolore lombare'); stringa vuota se la domanda non ne cita una") String condizione,
        @JsonPropertyDescription("Regione anatomica della condizione; 'altro' se non rientra nella lista o se non c'è una condizione") Regione regione,
        @JsonPropertyDescription("Evoluzione richiesta della condizione; 'qualsiasi' se la domanda non la specifica") AndamentoRichiesto andamento,
        @JsonPropertyDescription("Ampiezza in mesi della finestra temporale ('ultimi 3 mesi' -> 3, 'ultimo anno' -> 12); 0 se la domanda non ne indica una") int finestraMesi,
        @JsonPropertyDescription("Filtro sugli appuntamenti: 'ultimo_saltato' se il paziente deve aver saltato l'ultimo appuntamento, altrimenti 'qualsiasi'") Appuntamento appuntamento) {

    public enum AndamentoRichiesto {
        miglioramento, peggioramento, stazionario, qualsiasi
    }

    public enum Appuntamento {
        ultimo_saltato, qualsiasi
    }

    public QueryPlan {
        condizione = condizione == null ? "" : condizione.trim();
        regione = regione == null ? Regione.altro : regione;
        andamento = andamento == null ? AndamentoRichiesto.qualsiasi : andamento;
        appuntamento = appuntamento == null ? Appuntamento.qualsiasi : appuntamento;
        finestraMesi = Math.max(0, finestraMesi);
    }

    public boolean regioneNota() {
        return regione != Regione.altro;
    }

    public boolean condizioneLibera() {
        return !regioneNota() && !condizione.isBlank();
    }
}
