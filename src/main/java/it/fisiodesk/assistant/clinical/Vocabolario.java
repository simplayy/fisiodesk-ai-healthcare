package it.fisiodesk.assistant.clinical;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Dizionario italiano/inglese di termini clinici. È il cuore del fallback senza modello e viene
 * usato anche per riconoscere la regione nelle domande in linguaggio naturale.
 */
public final class Vocabolario {

    /** Espressioni regolari sul testo normalizzato: i termini brevi hanno confini di parola ("dita" non deve scattare su "rigidita"). */
    private static final Map<Regione, List<Pattern>> REGIONI = new LinkedHashMap<>();

    static {
        REGIONI.put(Regione.lombare, patterns("lombar", "lombalg", "mal di schiena", "bassa schiena", "low back", "colpo della strega",
                "lombosciatalg", "sciatalg", "sciatic", "\\bschiena\\b"));
        REGIONI.put(Regione.cervicale, patterns("cervic", "\\bcollo\\b", "torcicollo", "\\bneck\\b"));
        REGIONI.put(Regione.dorsale, patterns("dorsal", "interscapol"));
        REGIONI.put(Regione.spalla, patterns("\\bspall[ae]\\b", "cuffia dei rotatori", "shoulder", "capsulite"));
        REGIONI.put(Regione.gomito, patterns("\\bgomit[oi]\\b", "epicondil", "epitrocle", "\\belbow\\b"));
        REGIONI.put(Regione.polso_mano, patterns("\\bpols[oi]\\b", "\\bman[oi]\\b", "tunnel carpale", "\\bdit[ao]\\b", "\\bwrist\\b"));
        REGIONI.put(Regione.anca, patterns("\\banca\\b", "coxalg", "coxartros", "\\bhip\\b"));
        REGIONI.put(Regione.ginocchio, patterns("ginocchi", "gonalg", "gonartros", "menisc", "crociat", "\\bknee\\b"));
        REGIONI.put(Regione.caviglia_piede, patterns("cavigli", "\\bpied[ei]\\b", "tallon", "fascite plantare", "achille", "\\bankle\\b"));
    }

    private static final List<Pattern> PEGGIORAMENTO = patterns("peggior", "aggrav", "non migliora", "non risponde", "si e estes[oa]", "esteso anche",
            "recidiva", "riacutizz", "ricomparsa");
    private static final List<Pattern> STAZIONARIO = patterns("stazionar", "invariat", "persist", "immutat", "nessun cambiamento", "nessuna variazione");
    private static final List<Pattern> MIGLIORAMENTO = patterns("miglior", "sta meglio", "sta molto meglio", "sta decisamente meglio", "sta benissimo",
            "progress", "recuper", "risol", "guarit", "guarigione", "sollievo", "diminui", "ridott", "ridurr", "riduzione", "nessun dolore",
            "senza dolore", "quasi senza", "risponde bene", "buona risposta", "eccellente", "ottim", "sopportabile");
    private static final List<Pattern> PRIMA_VISITA = patterns("prima valutazione", "valutazione iniziale", "prima visita", "primo trattamento",
            "prima seduta", "iniziato ciclo", "iniziato trattamento");

    private Vocabolario() {
    }

    /** Minuscolo, senza accenti, spazi compattati: rende i confronti tolleranti a "è"/"e'" e simili. */
    public static String normalizza(String testo) {
        String s = Normalizer.normalize(testo == null ? "" : testo, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ITALIAN)
                .replace('\'', ' ');
        return s.replaceAll("\\s+", " ").trim();
    }

    public static List<Regione> regioni(String testoNormalizzato) {
        return REGIONI.entrySet().stream()
                .filter(e -> contieneUno(testoNormalizzato, e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    public static Optional<Regione> primaRegione(String testoNormalizzato) {
        return regioni(testoNormalizzato).stream().findFirst();
    }

    public static Andamento andamento(String testoNormalizzato) {
        if (contieneUno(testoNormalizzato, PEGGIORAMENTO)) {
            return Andamento.peggioramento;
        }
        if (contieneUno(testoNormalizzato, STAZIONARIO)) {
            return Andamento.stazionario;
        }
        if (contieneUno(testoNormalizzato, MIGLIORAMENTO)) {
            return Andamento.miglioramento;
        }
        return Andamento.non_determinabile;
    }

    public static boolean primaVisita(String testoNormalizzato) {
        return contieneUno(testoNormalizzato, PRIMA_VISITA);
    }

    static boolean contieneUno(String testo, List<Pattern> termini) {
        return termini.stream().anyMatch(p -> p.matcher(testo).find());
    }

    private static List<Pattern> patterns(String... regex) {
        return java.util.Arrays.stream(regex).map(Pattern::compile).toList();
    }
}
