package it.fisiodesk.assistant.query;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import it.fisiodesk.assistant.clinical.Regione;
import it.fisiodesk.assistant.clinical.Vocabolario;
import it.fisiodesk.assistant.query.QueryPlan.AndamentoRichiesto;
import it.fisiodesk.assistant.query.QueryPlan.Appuntamento;

/**
 * Parser deterministico delle domande. Copre la famiglia di domande per cui il sistema è pensato
 * (condizione, andamento, finestra, appuntamento saltato) e risponde in microsecondi.
 * <p>
 * {@link #pianifica(String)} restituisce un piano solo quando il vocabolario ha davvero ricondotto
 * la domanda a filtri concreti: è il segnale che al modello non resta niente da aggiungere. Quando
 * invece la domanda esce dal vocabolario o è ambigua, torna vuoto e decide il modello.
 */
@Component
public class RulePlanner implements Planner {

    private static final Pattern FINESTRA = Pattern.compile("ultim[oiae]\\s+(\\d+)?\\s*(mes|settiman|giorn|ann)");
    private static final Pattern SALTATO = Pattern.compile("saltat|non (si e |si sono )?presentat|no.?show|mancat|assent");
    /** "pazienti con X che..." -> X, fermandosi a congiunzioni, verbi e parole di andamento. */
    private static final Pattern CONDIZIONE = Pattern.compile(
            "\\b(?:con|per)\\s+(?:la |il |lo |i |le |gli |un |una |dei |delle |degli )?([^,;?]+?)"
            + "(?=\\s+(?:che|e|ed|ma|negli|nell|nei|con|i quali|le quali|in|sono|hanno|ha|non|miglior\\w*|peggior\\w*|stazionar\\w*|stabil\\w*)\\b|\\s*\\?|$)");
    /** "non hanno avuto miglioramenti": il senso si ribalta e le regole non sanno in cosa. */
    private static final Pattern ANDAMENTO_NEGATO = Pattern.compile(
            "\\b(?:non|senza|nessun\\w*)\\b(?:\\W+\\w+){0,3}\\W+(?:miglior\\w*|progress\\w*|recuper\\w*|guarit\\w*)");

    @Override
    public Optional<QueryPlan> pianifica(String domanda) {
        String norm = Vocabolario.normalizza(domanda);
        QueryPlan piano = interpreta(norm);
        return copre(norm, piano) ? Optional.of(piano) : Optional.empty();
    }

    /** Quello che le regole riescono comunque a estrarre: si usa quando il modello non risponde. */
    public QueryPlan ripiego(String domanda) {
        return interpreta(Vocabolario.normalizza(domanda));
    }

    /**
     * Le regole bastano se hanno prodotto almeno un filtro concreto e non hanno incontrato
     * né una condizione fuori tassonomia né una negazione che ne ribalti il senso.
     */
    private static boolean copre(String norm, QueryPlan piano) {
        if (ANDAMENTO_NEGATO.matcher(norm).find() || piano.condizioneLibera()) {
            return false;
        }
        return piano.regioneNota() || piano.andamento() != AndamentoRichiesto.qualsiasi || piano.finestraMesi() > 0
                || piano.appuntamento() != Appuntamento.qualsiasi;
    }

    private static QueryPlan interpreta(String norm) {
        Regione regione = Vocabolario.primaRegione(norm).orElse(Regione.altro);
        return new QueryPlan(condizione(norm, regione), regione, andamento(norm), finestra(norm), appuntamento(norm));
    }

    private static String condizione(String norm, Regione regione) {
        Matcher m = CONDIZIONE.matcher(norm);
        if (m.find()) {
            return m.group(1).trim();
        }
        return regione == Regione.altro ? "" : "dolore " + regione.name().replace('_', ' ');
    }

    private static AndamentoRichiesto andamento(String norm) {
        if (norm.contains("peggior") || norm.contains("aggrav")) {
            return AndamentoRichiesto.peggioramento;
        }
        if (norm.contains("stazionar") || norm.contains("invariat") || norm.contains("non migliora")) {
            return AndamentoRichiesto.stazionario;
        }
        if (norm.contains("miglior") || norm.contains("progress") || norm.contains("recuper") || norm.contains("guarit")) {
            return AndamentoRichiesto.miglioramento;
        }
        return AndamentoRichiesto.qualsiasi;
    }

    static int finestra(String norm) {
        Matcher m = FINESTRA.matcher(norm);
        if (!m.find()) {
            return 0;
        }
        int n = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
        return switch (m.group(2)) {
            case "ann" -> n * 12;
            case "settiman", "giorn" -> 1;
            default -> n;
        };
    }

    private static Appuntamento appuntamento(String norm) {
        return SALTATO.matcher(norm).find() ? Appuntamento.ultimo_saltato : Appuntamento.qualsiasi;
    }
}
