package it.fisiodesk.assistant.query;

import java.util.Optional;

public interface Planner {

    Optional<QueryPlan> pianifica(String domanda);
}
