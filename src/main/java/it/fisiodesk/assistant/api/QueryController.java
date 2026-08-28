package it.fisiodesk.assistant.api;

import java.time.LocalDate;

import org.bson.types.ObjectId;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import it.fisiodesk.assistant.query.PlanService;
import it.fisiodesk.assistant.query.QueryService;
import it.fisiodesk.assistant.query.SearchResult;

@RestController
@RequestMapping("/api/ricerca")
public class QueryController {

    public record Richiesta(
            @NotBlank @Size(max = 500) String domanda,
            @Nullable String professionistaId,
            @Nullable LocalDate dataRiferimento) {
    }

    private final QueryService query;
    private final PlanService planner;

    public QueryController(QueryService query, PlanService planner) {
        this.query = query;
        this.planner = planner;
    }

    @PostMapping
    public SearchResult cerca(@Valid @RequestBody Richiesta r) {
        ObjectId professionista = r.professionistaId() == null || r.professionistaId().isBlank() ? null : Ids.objectId(r.professionistaId(), "professionista_id");
        return query.rispondi(r.domanda(), professionista, r.dataRiferimento());
    }

    /** Solo l'interpretazione della domanda, utile per capire cosa verrà cercato. */
    @GetMapping("/piano")
    public SearchResult.Piano piano(@RequestParam @NotBlank @Size(max = 500) String domanda) {
        PlanService.Planned p = planner.pianifica(domanda);
        return SearchResult.Piano.di(p.piano(), p.origine());
    }
}
