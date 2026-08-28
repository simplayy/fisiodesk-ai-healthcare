package it.fisiodesk.assistant.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Date;

import org.bson.Document;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import it.fisiodesk.assistant.config.AssistantProperties;

/**
 * "Oggi" per i filtri temporali. In produzione è la data corrente; sul dataset di prova (2024) si usa
 * la data dell'ultimo evento in calendario, oppure quella passata nella richiesta o in configurazione.
 */
@Component
public class ReferenceDates {

    public record Periodo(LocalDate da, LocalDate a, Instant inizio, Instant fine) {
    }

    private final MongoTemplate mongo;
    private final AssistantProperties props;

    public ReferenceDates(MongoTemplate mongo, AssistantProperties props) {
        this.mongo = mongo;
        this.props = props;
    }

    public LocalDate riferimento(@Nullable LocalDate richiesta) {
        if (richiesta != null) {
            return richiesta;
        }
        if (props.referenceDate() != null && !props.referenceDate().isBlank()) {
            return LocalDate.parse(props.referenceDate().trim());
        }
        Document ultimo = mongo.findOne(new Query().with(Sort.by(Sort.Direction.DESC, "data")).limit(1), Document.class, "eventi_calendario");
        Date data = ultimo == null ? null : ultimo.getDate("data");
        return data == null ? LocalDate.now(ZoneOffset.UTC) : data.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    public Periodo periodo(LocalDate riferimento, int mesi) {
        Instant fine = riferimento.atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC);
        LocalDate da = mesi > 0 ? riferimento.minusMonths(mesi) : LocalDate.EPOCH;
        return new Periodo(da, riferimento, da.atStartOfDay(ZoneOffset.UTC).toInstant(), fine);
    }
}
