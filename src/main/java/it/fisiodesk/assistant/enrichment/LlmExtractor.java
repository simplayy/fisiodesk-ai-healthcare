package it.fisiodesk.assistant.enrichment;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import it.fisiodesk.assistant.clinical.ClinicalFacts;

/**
 * Estrazione con modello linguistico e output vincolato allo schema JSON di {@link ClinicalFacts}.
 * Con Ollama lo schema diventa la grammatica di decodifica, con OpenAI/Anthropic usa gli structured
 * output nativi: in entrambi i casi non c'è parsing "a mano".
 */
public class LlmExtractor implements Extractor {

    static final int PROMPT_VERSION = 1;

    static final String SYSTEM = """
            Sei un assistente clinico per fisioterapisti. Ricevi una nota clinica (italiano o inglese) e la converti in dati strutturati.
            Regole:
            - "problemi": un elemento per ogni regione anatomica di cui la nota parla (di solito una).
            - "regione": lombalgia, mal di schiena, low back pain, rachialgia lombare, colpo della strega, dolore alla bassa schiena, sciatalgia -> "lombare". \
            Cervicalgia, dolore al collo, torcicollo -> "cervicale". Spalla congelata, capsulite -> "spalla". Usa "altro" solo se nessuna regione della lista è adatta.
            - "condizione": nome breve normalizzato (es. "lombalgia", "cervicalgia", "spalla congelata").
            - "andamento": evoluzione di QUELLA condizione secondo la nota. "miglioramento" solo se la nota dice esplicitamente che c'è stato un progresso, \
            una riduzione del dolore, un recupero o una risoluzione. "peggioramento" se indica un aggravamento o l'assenza di risposta alle terapie. \
            "stazionario" se dice che la situazione è invariata o persistente. "non_determinabile" per una prima valutazione o quando manca un confronto con il passato.
            - "vas": punteggi del dolore su scala 0-10 citati nella nota, in ordine cronologico (es. "da 8/10 a 3/10" -> [8, 3]); lista vuota se assenti.
            - "sintesi": una frase in italiano, massimo 20 parole, utile al professionista.
            Rispondi solo con il JSON richiesto.
            """;

    private static final Logger log = LoggerFactory.getLogger(LlmExtractor.class);

    private final ChatClient chat;
    private final String nome;

    public LlmExtractor(ChatClient chat, String nome) {
        this.chat = chat;
        this.nome = nome;
    }

    @Override
    public Optional<ClinicalFacts> estrai(String testo) {
        try {
            ClinicalFacts facts = chat.prompt()
                    .system(SYSTEM)
                    .user(testo)
                    .call()
                    .entity(ClinicalFacts.class, spec -> spec.useProviderStructuredOutput());
            if (facts == null || facts.isEmpty()) {
                log.warn("Il modello non ha restituito problemi per la nota: {}", abbrevia(testo));
                return Optional.empty();
            }
            return Optional.of(facts);
        } catch (RuntimeException e) {
            throw new ModelUnavailableException("Estrazione fallita con " + nome + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String nome() {
        return nome;
    }

    private static String abbrevia(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
