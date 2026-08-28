package it.fisiodesk.assistant.query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.jspecify.annotations.Nullable;

import it.fisiodesk.assistant.clinical.Andamento;
import it.fisiodesk.assistant.clinical.Regione;

public record SearchResult(
        String domanda,
        Piano piano,
        Periodo periodo,
        /** "strutturata" (tag estratti dal modello) o "semantica" (vector search sul testo della condizione). */
        String modalita,
        List<Paziente> risultati,
        Tempi tempi,
        List<String> avvisi) {

    public record Piano(String condizione, Regione regione, QueryPlan.AndamentoRichiesto andamento, int finestraMesi,
            QueryPlan.Appuntamento appuntamento, String origine) {

        public static Piano di(QueryPlan p, String origine) {
            return new Piano(p.condizione(), p.regione(), p.andamento(), p.finestraMesi(), p.appuntamento(), origine);
        }
    }

    public record Periodo(LocalDate da, LocalDate a) {
    }

    public record Paziente(Anagrafica paziente, String professionistaId, List<Evidenza> evidenze, @Nullable Appuntamento ultimoAppuntamento,
            @Nullable Double punteggio) {
    }

    public record Anagrafica(String id, String nome, String cognome, @Nullable Integer eta, @Nullable String telefono, @Nullable String email,
            @Nullable String stato) {
    }

    public record Evidenza(Instant data, String collezione, String sintesi, Regione regione, String condizione, Andamento andamento,
            List<Integer> vas, String fonte, @Nullable Double punteggio) {
    }

    public record Appuntamento(Instant data, String stato, @Nullable Integer durata, @Nullable String note) {
    }

    public record Tempi(long pianoMs, long ricercaMs, long totaleMs) {
    }
}
