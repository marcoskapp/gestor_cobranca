package br.com.gestorcobranca.helpdesk.domain.model;

import java.util.Set;

/** Avaliação de uma resposta pelo usuário (👍/👎). */
public enum Feedback {
    UP, DOWN;

    /** Motivos da avaliação negativa (catálogo fechado: texto livre não é guardado). */
    public static final Set<String> DOWN_REASONS = Set.of("INCORRECT", "NOT_HELPFUL", "INCOMPLETE", "OUT_OF_DATE",
            "OTHER");
}
