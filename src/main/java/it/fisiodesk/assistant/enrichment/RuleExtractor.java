package it.fisiodesk.assistant.enrichment;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import it.fisiodesk.assistant.clinical.Andamento;
import it.fisiodesk.assistant.clinical.ClinicalFacts;
import it.fisiodesk.assistant.clinical.ClinicalFacts.Problema;
import it.fisiodesk.assistant.clinical.Regione;
import it.fisiodesk.assistant.clinical.Vocabolario;

/**
 * Estrattore deterministico a dizionario. Serve a tre cose: rendere il sistema utilizzabile
 * prima che il modello abbia finito, coprire il caso in cui il modello non c'è, e dare ai test
 * un comportamento riproducibile. Precisione inferiore al modello sui casi ambigui (negazioni
 * lontane, più regioni nella stessa nota), che qui vengono trattati a livello di intera nota.
 */
@Component
public class RuleExtractor implements Extractor {

    private static final Pattern VAS = Pattern.compile("(\\d{1,2})\\s*/\\s*10");
    private static final int MAX_SINTESI = 140;

    @Override
    public Optional<ClinicalFacts> estrai(String testo) {
        String norm = Vocabolario.normalizza(testo);
        List<Regione> regioni = Vocabolario.regioni(norm);
        Andamento andamento = Vocabolario.andamento(norm);
        if (Vocabolario.primaVisita(norm) && andamento != Andamento.miglioramento) {
            andamento = Andamento.non_determinabile;
        }
        List<Integer> vas = vas(testo);
        List<Regione> effettive = regioni.isEmpty() ? List.of(Regione.altro) : regioni;
        Andamento finale = andamento;
        List<Problema> problemi = effettive.stream().map(r -> new Problema(r, condizione(r, norm), finale, vas)).toList();
        return Optional.of(new ClinicalFacts(problemi, sintesi(testo)));
    }

    @Override
    public String nome() {
        return "regole";
    }

    static List<Integer> vas(String testo) {
        Matcher m = VAS.matcher(testo);
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (v <= 10) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    private static String condizione(Regione r, String norm) {
        return switch (r) {
            case lombare -> norm.contains("sciatalg") || norm.contains("sciatic") ? "lombosciatalgia" : "lombalgia";
            case cervicale -> "cervicalgia";
            case dorsale -> "dorsalgia";
            case spalla -> norm.contains("congelata") || norm.contains("capsulite") ? "spalla congelata" : "dolore alla spalla";
            case gomito -> "epicondilite";
            case polso_mano -> "dolore al polso/mano";
            case anca -> "coxalgia";
            case ginocchio -> "gonalgia";
            case caviglia_piede -> "dolore alla caviglia/piede";
            case altro -> "non classificata";
        };
    }

    private static String sintesi(String testo) {
        String t = testo.trim();
        int punto = t.indexOf(". ");
        String prima = punto > 20 ? t.substring(0, punto + 1) : t;
        return prima.length() <= MAX_SINTESI ? prima : prima.substring(0, MAX_SINTESI - 1) + "…";
    }
}
