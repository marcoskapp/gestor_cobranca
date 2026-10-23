package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.DistributionStrategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Planejamento da distribuição de casos entre operadores (F07), sem efeitos colaterais.
 * <ul>
 *   <li>{@code ROUND_ROBIN}: rodízio pela ordem dos ids dos membros, continuando depois do
 *       último que recebeu caso ({@code cursor}), para que distribuições sucessivas sejam
 *       justas;</li>
 *   <li>{@code LEAST_LOADED}: cada caso vai para o membro com menos casos ativos (empate pelo
 *       id), contando os casos já planejados.</li>
 * </ul>
 * Em ambas, um membro só recebe caso de credor do seu escopo e até a sua capacidade. Casos sem
 * membro possível ficam em {@code unassigned}.
 */
public final class CaseDistributor {

    private CaseDistributor() {
    }

    /**
     * @param load      casos ativos já atribuídos ao membro (na fila)
     * @param capacity  máximo de casos ativos (nulo = ilimitado)
     */
    public record Member(UUID userId, int load, Integer capacity, boolean allCreditors, Set<UUID> creditorIds) {

        public Member {
            creditorIds = creditorIds == null ? Set.of() : Set.copyOf(creditorIds);
        }

        boolean canTake(UUID creditorId, int currentLoad) {
            return (allCreditors || creditorIds.contains(creditorId)) && (capacity == null || currentLoad < capacity);
        }
    }

    public record Candidate(UUID caseId, UUID creditorId) {
    }

    public record Assignment(UUID caseId, UUID userId) {
    }

    /** @param cursor último membro que recebeu caso (para o próximo rodízio) */
    public record Plan(List<Assignment> assignments, List<UUID> unassigned, UUID cursor) {
    }

    public static Plan plan(List<Candidate> cases, List<Member> members, DistributionStrategy strategy, UUID cursor) {
        if (strategy == DistributionStrategy.PULL) {
            throw new IllegalArgumentException("Fila sob demanda não é distribuída em lote");
        }
        List<Member> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparing(Member::userId));
        Map<UUID, Integer> loads = new HashMap<>();
        ordered.forEach(m -> loads.put(m.userId(), m.load()));
        List<Assignment> assignments = new ArrayList<>();
        List<UUID> unassigned = new ArrayList<>();
        UUID last = cursor;
        int pointer = startAfter(ordered, cursor);
        for (Candidate candidate : cases) {
            Member chosen = null;
            if (strategy == DistributionStrategy.ROUND_ROBIN) {
                for (int i = 0; i < ordered.size(); i++) {
                    int idx = (pointer + i) % ordered.size();
                    Member m = ordered.get(idx);
                    if (m.canTake(candidate.creditorId(), loads.get(m.userId()))) {
                        chosen = m;
                        pointer = (idx + 1) % ordered.size();
                        break;
                    }
                }
            } else {
                for (Member m : ordered) {
                    int load = loads.get(m.userId());
                    if (m.canTake(candidate.creditorId(), load)
                            && (chosen == null || load < loads.get(chosen.userId()))) {
                        chosen = m;
                    }
                }
            }
            if (chosen == null) {
                unassigned.add(candidate.caseId());
            } else {
                assignments.add(new Assignment(candidate.caseId(), chosen.userId()));
                loads.merge(chosen.userId(), 1, Integer::sum);
                last = chosen.userId();
            }
        }
        return new Plan(assignments, unassigned, last);
    }

    private static int startAfter(List<Member> ordered, UUID cursor) {
        if (cursor == null || ordered.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).userId().compareTo(cursor) > 0) {
                return i;
            }
        }
        return 0;
    }
}
