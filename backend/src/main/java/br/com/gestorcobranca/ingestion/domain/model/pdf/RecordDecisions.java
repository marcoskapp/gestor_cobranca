package br.com.gestorcobranca.ingestion.domain.model.pdf;

import java.util.Set;
import java.util.TreeSet;

/**
 * Decisões do usuário sobre os títulos da revisão corrente do mapa: excluídos do lote e, na leitura visual,
 * confirmados um a um. Valem só para a revisão em que foram feitas (a numeração muda com o mapa).
 */
public record RecordDecisions(Set<Integer> excluded, Set<Integer> confirmed) {

    public RecordDecisions {
        excluded = excluded == null ? Set.of() : Set.copyOf(new TreeSet<>(excluded));
        confirmed = confirmed == null ? Set.of() : Set.copyOf(new TreeSet<>(confirmed));
    }

    public static RecordDecisions none() {
        return new RecordDecisions(Set.of(), Set.of());
    }

    public enum Decision { EXCLUDE, INCLUDE, CONFIRM }

    public RecordDecisions apply(Decision decision, Set<Integer> ordinals) {
        TreeSet<Integer> ex = new TreeSet<>(excluded);
        TreeSet<Integer> co = new TreeSet<>(confirmed);
        switch (decision) {
            case EXCLUDE -> {
                ex.addAll(ordinals);
                co.removeAll(ordinals);
            }
            case INCLUDE -> ex.removeAll(ordinals);
            case CONFIRM -> {
                co.addAll(ordinals);
                ex.removeAll(ordinals);
            }
        }
        return new RecordDecisions(ex, co);
    }

    public boolean isExcluded(int ordinal) {
        return excluded.contains(ordinal);
    }

    public boolean isConfirmed(int ordinal) {
        return confirmed.contains(ordinal);
    }
}
