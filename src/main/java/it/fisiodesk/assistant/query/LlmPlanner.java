package it.fisiodesk.assistant.query;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

public class LlmPlanner implements Planner {

    static final String SYSTEM = """
            Trasformi domande di professionisti sanitari sui propri pazienti in un piano di ricerca strutturato.
            - "condizione": la condizione clinica citata, con le parole della domanda; vuota se non c'è.
            - "regione": la regione anatomica della condizione. Dolore lombare, lombalgia, mal di schiena, low back pain, sciatalgia -> "lombare"; \
            cervicalgia, dolore al collo -> "cervicale"; spalla congelata, capsulite -> "spalla". "altro" se non è nella lista o se non c'è una condizione.
            - "andamento": "miglioramento" se la domanda chiede pazienti migliorati/che hanno fatto progressi, "peggioramento" se peggiorati, \
            "stazionario" se stabili, altrimenti "qualsiasi".
            - "finestra_mesi": i mesi della finestra temporale citata ("ultimi 3 mesi" -> 3, "ultimo anno" -> 12, "ultimo mese" -> 1); 0 se assente.
            - "appuntamento": "ultimo_saltato" se la domanda chiede chi ha saltato / non si è presentato all'ultimo appuntamento, altrimenti "qualsiasi".
            Rispondi solo con il JSON richiesto.
            """;

    private static final Logger log = LoggerFactory.getLogger(LlmPlanner.class);

    private final ChatClient chat;

    public LlmPlanner(ChatClient chat) {
        this.chat = chat;
    }

    @Override
    public Optional<QueryPlan> pianifica(String domanda) {
        try {
            QueryPlan plan = chat.prompt().system(SYSTEM).user(domanda).call()
                    .entity(QueryPlan.class, spec -> spec.useProviderStructuredOutput());
            return Optional.ofNullable(plan);
        } catch (RuntimeException e) {
            log.warn("Pianificazione con il modello fallita: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
