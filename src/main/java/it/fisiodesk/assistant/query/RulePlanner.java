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
 * (condizione, andamento, finestra, appuntamento saltato) ed è la rete di sicurezza quando il modello
 * è lento o assente. Risponde sempre.
 */
@Component
public class RulePlanner implements Planner {

    private static final Pattern FINESTRA = Pattern.compile("ultim[oiae]\\s+(\\d+)?\\s*(mes|settiman|giorn|ann)");
    private static final Pattern SALTATO = Pattern.compile("saltat|non (si e |si sono )?presentat|no.?show|mancat|assent");
    /** "pazienti con X che..." -> X, fermandosi a congiunzioni, verbi e parole di andamento. */
    private static final Pattern CONDIZIONE = Pattern.compile(
            "\\b(?:con|per)\\s+(?:la |il |lo |i |le |gli |un |una |dei |delle |degli )?([^,;?]+?)"
            + "(?=\\s+(?:che|e|ed|ma|negli|nell|nei|con|i quali|le quali|in|sono|hanno|ha|non|miglior\\w*|peggior\\w*|stazionar\\w*|stabil\\w*)\\b|\\s*\\?|$)");

    @Override
    public Optional<QueryPlan> pianifica(String domanda) {
        String norm = Vocabolario.normalizza(domanda);
        Regione regione = Vocabolario.primaRegione(norm).orElse(Regione.altro);
        String condizione = condizione(norm, regione);
        return Optional.of(new QueryPlan(condizione, regione, andamento(norm), finestra(norm), appuntamento(norm)));
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
