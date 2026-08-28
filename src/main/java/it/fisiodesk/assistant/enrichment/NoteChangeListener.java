package it.fisiodesk.assistant.enrichment;

import java.util.List;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.ChangeStreamOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.messaging.ChangeStreamRequest;
import org.springframework.data.mongodb.core.messaging.DefaultMessageListenerContainer;
import org.springframework.data.mongodb.core.messaging.MessageListener;
import org.springframework.data.mongodb.core.messaging.MessageListenerContainer;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;

import it.fisiodesk.assistant.config.AssistantProperties;

/**
 * Quando il gestionale scrive o modifica una nota, l'annotazione viene rifatta entro pochi secondi
 * senza aspettare la riconciliazione periodica. Richiede un replica set (Atlas lo è sempre).
 */
@Component
public class NoteChangeListener {

    private static final Logger log = LoggerFactory.getLogger(NoteChangeListener.class);

    private final MongoTemplate mongo;
    private final EnrichmentService enrichment;
    private final AssistantProperties props;
    private MessageListenerContainer container;

    public NoteChangeListener(MongoTemplate mongo, EnrichmentService enrichment, AssistantProperties props) {
        this.mongo = mongo;
        this.enrichment = enrichment;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void avvia() {
        if (!props.enrichment().enabled()) {
            return;
        }
        container = new DefaultMessageListenerContainer(mongo);
        container.start();
        for (String collezione : NoteRepository.COLLEZIONI) {
            MessageListener<ChangeStreamDocument<Document>, Document> listener = msg -> onChange(collezione, msg.getBody());
            ChangeStreamRequest<Document> request = ChangeStreamRequest.<Document>builder()
                    .collection(collezione)
                    .publishTo(listener)
                    .filter(Aggregation.newAggregation(Aggregation.match(Criteria.where("operationType").in(List.of("insert", "update", "replace")))))
                    .fullDocumentLookup(FullDocument.UPDATE_LOOKUP)
                    .build();
            container.register(request, Document.class, e -> log.warn("Change stream su {} interrotto ({}); resta attiva la riconciliazione periodica",
                    collezione, e.getMessage()));
        }
        log.info("Change stream attivo su {}", NoteRepository.COLLEZIONI);
    }

    private void onChange(String collezione, Document doc) {
        if (doc == null) {
            return;
        }
        SourceNote nota = SourceNote.from(collezione, doc);
        try {
            Annotation a = enrichment.elabora(nota, true);
            log.info("Nota {} annotata in tempo reale ({})", a.id(), a.fonte());
        } catch (ModelUnavailableException e) {
            enrichment.elabora(nota, false);
            log.info("Nota {} annotata a regole (modello non disponibile), verrà rielaborata", nota.annotationId());
        }
    }
}
