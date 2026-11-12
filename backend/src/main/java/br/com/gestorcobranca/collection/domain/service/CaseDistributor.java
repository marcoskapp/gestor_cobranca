package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.DistributionStrategy;
import br.com.gestorcobranca.collection.domain.model.PerformanceTier;
import br.com.gestorcobranca.collection.domain.model.PotentialBand;
import br.com.gestorcobranca.collection.domain.model.TierPolicy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
 *       id), contando os casos já planejados;</li>
 *   <li>{@code TIERED} (por nível de desempenho, distribuicao-por-desempenho.md §4.2): casos em ordem de valor
 *       esperado (probabilidade do score × saldo elegível), em faixas de potencial por tercis; a faixa alta vai
 *       primeiro aos níveis A e B, a média a B e C, a baixa a C e D; esgotado o nível preferido, o caso vai ao
 *       próximo nível com capacidade (nunca fica sem dono por causa do nível). A capacidade de cada membro é a da
 *       fila × o multiplicador do seu nível. A fatia de desenvolvimento entrega parte dos casos altos por rodízio a
 *       todos os membros (faixa {@code DEVELOPMENT}), usando o mesmo {@code cursor} do rodízio.</li>
 * </ul>
 * Em todas, um membro só recebe caso de credor do seu escopo e até a sua capacidade. Casos sem
 * membro possível ficam em {@code unassigned}.
 */
public final class CaseDistributor {

    /** Probabilidade usada quando nenhum caso da seleção tem score: o valor esperado vira o próprio saldo. */
    static final double NO_SCORE_PROBABILITY = 1.0;

    private static final Map<PotentialBand, List<PerformanceTier>> PREFERENCE = Map.of(
            PotentialBand.HIGH, List.of(PerformanceTier.A, PerformanceTier.B, PerformanceTier.C, PerformanceTier.D),
            PotentialBand.MEDIUM, List.of(PerformanceTier.B, PerformanceTier.C, PerformanceTier.A, PerformanceTier.D),
            PotentialBand.LOW, List.of(PerformanceTier.C, PerformanceTier.D, PerformanceTier.B, PerformanceTier.A));

    private CaseDistributor() {
    }

    /**
     * @param load      casos ativos já atribuídos ao membro (na fila)
     * @param capacity  máximo de casos ativos (nulo = ilimitado); em {@code TIERED}, a capacidade da fila, que o
     *                  planejador multiplica pelo nível
     * @param tier      nível de desempenho publicado (nulo = sem nível; vale o padrão da assessoria)
     */
    public record Member(UUID userId, int load, Integer capacity, boolean allCreditors, Set<UUID> creditorIds,
                         PerformanceTier tier) {

        public Member {
            creditorIds = creditorIds == null ? Set.of() : Set.copyOf(creditorIds);
        }

        public Member(UUID userId, int load, Integer capacity, boolean allCreditors, Set<UUID> creditorIds) {
            this(userId, load, capacity, allCreditors, creditorIds, null);
        }

        boolean inScope(UUID creditorId) {
            return allCreditors || creditorIds.contains(creditorId);
        }

        boolean canTake(UUID creditorId, int currentLoad) {
            return inScope(creditorId) && (capacity == null || currentLoad < capacity);
        }
    }

    /**
     * @param score   propensão 0–1000 do scoring (nulo = sem score); probabilidade = score ÷ 1000
     * @param balance saldo elegível do caso (nulo = zero)
     * @param band    faixa já calculada sobre todos os casos disponíveis da fila (nulo = o planejador calcula os
     *                tercis sobre os casos recebidos)
     */
    public record Candidate(UUID caseId, UUID creditorId, Integer score, BigDecimal balance, PotentialBand band) {

        public Candidate(UUID caseId, UUID creditorId) {
            this(caseId, creditorId, null, null, null);
        }
    }

    /**
     * @param tier nível efetivo de quem recebeu (só em {@code TIERED})
     * @param band faixa de potencial do caso ou {@code DEVELOPMENT} (só em {@code TIERED})
     */
    public record Assignment(UUID caseId, UUID userId, PerformanceTier tier, PotentialBand band) {

        public Assignment(UUID caseId, UUID userId) {
            this(caseId, userId, null, null);
        }
    }

    /** @param cursor último membro que recebeu caso por rodízio (para o próximo rodízio) */
    public record Plan(List<Assignment> assignments, List<UUID> unassigned, UUID cursor) {
    }

    /** Caso com valor esperado e faixa de potencial, na ordem de entrega (maior valor esperado primeiro). */
    public record Ranked(Candidate candidate, double expectedValue, PotentialBand band) {
    }

    public static Plan plan(List<Candidate> cases, List<Member> members, DistributionStrategy strategy, UUID cursor) {
        return plan(cases, members, strategy, cursor, TierPolicy.DEFAULT);
    }

    public static Plan plan(List<Candidate> cases, List<Member> members, DistributionStrategy strategy, UUID cursor,
                            TierPolicy policy) {
        if (strategy == DistributionStrategy.PULL) {
            throw new IllegalArgumentException("Fila sob demanda não é distribuída em lote");
        }
        if (strategy == DistributionStrategy.TIERED) {
            return tiered(cases, members, cursor, policy == null ? TierPolicy.DEFAULT : policy);
        }
        List<Member> ordered = sorted(members);
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

    // ------------------------------------------------------------------ TIERED

    private static Plan tiered(List<Candidate> cases, List<Member> members, UUID cursor, TierPolicy policy) {
        List<Member> ordered = sorted(members);
        Map<UUID, Integer> loads = new HashMap<>();
        Map<UUID, Integer> capacities = new HashMap<>();
        Map<UUID, PerformanceTier> tiers = new HashMap<>();
        for (Member m : ordered) {
            PerformanceTier tier = policy.effective(m.tier());
            loads.put(m.userId(), m.load());
            capacities.put(m.userId(), policy.effectiveCapacity(m.capacity(), tier));
            tiers.put(m.userId(), tier);
        }
        List<Ranked> ranked = rank(cases);
        Set<UUID> development = developmentCases(ranked, policy.developmentShare());
        List<Assignment> assignments = new ArrayList<>();
        List<UUID> unassigned = new ArrayList<>();
        UUID last = cursor;
        int pointer = startAfter(ordered, cursor);
        for (Ranked r : ranked) {
            UUID creditor = r.candidate().creditorId();
            Member chosen = null;
            PotentialBand band = r.band();
            if (development.contains(r.candidate().caseId())) {
                for (int i = 0; i < ordered.size(); i++) {
                    int idx = (pointer + i) % ordered.size();
                    Member m = ordered.get(idx);
                    if (fits(m, creditor, loads, capacities)) {
                        chosen = m;
                        pointer = (idx + 1) % ordered.size();
                        last = m.userId();
                        band = PotentialBand.DEVELOPMENT;
                        break;
                    }
                }
            } else {
                for (PerformanceTier tier : PREFERENCE.get(r.band())) {
                    for (Member m : ordered) {
                        if (tiers.get(m.userId()) == tier && fits(m, creditor, loads, capacities)
                                && (chosen == null || loads.get(m.userId()) < loads.get(chosen.userId()))) {
                            chosen = m;
                        }
                    }
                    if (chosen != null) {
                        break;
                    }
                }
            }
            if (chosen == null) {
                unassigned.add(r.candidate().caseId());
            } else {
                assignments.add(new Assignment(r.candidate().caseId(), chosen.userId(), tiers.get(chosen.userId()),
                        band));
                loads.merge(chosen.userId(), 1, Integer::sum);
            }
        }
        return new Plan(assignments, unassigned, last);
    }

    private static boolean fits(Member m, UUID creditorId, Map<UUID, Integer> loads, Map<UUID, Integer> capacities) {
        Integer capacity = capacities.get(m.userId());
        return m.inScope(creditorId) && (capacity == null || loads.get(m.userId()) < capacity);
    }

    /**
     * Ordena por valor esperado (maior primeiro; empate mantém a ordem recebida) e define as faixas. Se todos os casos
     * já trazem a faixa (calculada pelo seletor sobre a fila inteira), mantém a ordem e as faixas recebidas.
     */
    public static List<Ranked> rank(List<Candidate> cases) {
        double meanProbability = meanProbability(cases);
        if (!cases.isEmpty() && cases.stream().allMatch(c -> c.band() != null)) {
            return cases.stream()
                    .map(c -> new Ranked(c, expectedValue(c.score(), c.balance(), meanProbability), c.band()))
                    .toList();
        }
        List<Candidate> byValue = new ArrayList<>(cases);
        byValue.sort(Comparator.comparingDouble(
                (Candidate c) -> expectedValue(c.score(), c.balance(), meanProbability)).reversed());
        List<Ranked> ranked = new ArrayList<>(byValue.size());
        for (int i = 0; i < byValue.size(); i++) {
            Candidate c = byValue.get(i);
            ranked.add(new Ranked(c, expectedValue(c.score(), c.balance(), meanProbability), tercile(i, byValue.size())));
        }
        return ranked;
    }

    /**
     * Faixa da posição {@code index} (0 = maior valor esperado) entre {@code size} casos: tercis como o
     * {@code ntile(3)} do PostgreSQL — os primeiros {@code size % 3} grupos recebem um caso a mais.
     */
    public static PotentialBand tercile(int index, int size) {
        int base = size / 3;
        int extra = size % 3;
        int high = base + (extra > 0 ? 1 : 0);
        int medium = base + (extra > 1 ? 1 : 0);
        if (index < high) {
            return PotentialBand.HIGH;
        }
        return index < high + medium ? PotentialBand.MEDIUM : PotentialBand.LOW;
    }

    /** Probabilidade média dos casos com score (score ÷ 1000); sem nenhum score, {@value #NO_SCORE_PROBABILITY}. */
    public static double meanProbability(List<Candidate> cases) {
        return cases.stream().filter(c -> c.score() != null).mapToDouble(c -> probability(c.score())).average()
                .orElse(NO_SCORE_PROBABILITY);
    }

    /** Valor esperado = probabilidade do score × saldo elegível; sem score, a probabilidade média informada. */
    public static double expectedValue(Integer score, BigDecimal balance, double meanProbability) {
        double p = score == null ? meanProbability : probability(score);
        return p * (balance == null ? 0d : balance.doubleValue());
    }

    /** Score do scoring é a probabilidade × 1000 (scoring.md §4), limitado a 0–1000. */
    static double probability(int score) {
        return Math.max(0, Math.min(1000, score)) / 1000d;
    }

    /**
     * Casos da faixa alta que vão pela fatia de desenvolvimento: {@code round(altos × share%)} casos, espalhados de
     * forma uniforme pela faixa (nem sempre os melhores nem sempre os piores), de modo determinístico.
     */
    static Set<UUID> developmentCases(List<Ranked> ranked, int share) {
        Set<UUID> chosen = new HashSet<>();
        if (share <= 0) {
            return chosen;
        }
        int i = 0;
        for (Ranked r : ranked) {
            if (r.band() != PotentialBand.HIGH) {
                continue;
            }
            if (Math.floorDiv((i + 1) * share + 50, 100) > Math.floorDiv(i * share + 50, 100)) {
                chosen.add(r.candidate().caseId());
            }
            i++;
        }
        return chosen;
    }

    // ------------------------------------------------------------------ próximo caso (TIERED)

    /**
     * Ordem das faixas no "próximo caso" de uma fila {@code TIERED}: A e B pegam a alta primeiro; C, a média; D, a
     * baixa; depois as demais, da mais próxima à mais distante (C: média, baixa, alta; D: baixa, média, alta). Na
     * vez de desenvolvimento, o operador C ou D recebe primeiro a faixa alta.
     */
    public static List<PotentialBand> nextCaseBands(PerformanceTier tier, boolean developmentTurn) {
        List<PotentialBand> order = switch (tier) {
            case A, B -> List.of(PotentialBand.HIGH, PotentialBand.MEDIUM, PotentialBand.LOW);
            case C -> List.of(PotentialBand.MEDIUM, PotentialBand.LOW, PotentialBand.HIGH);
            case D -> List.of(PotentialBand.LOW, PotentialBand.MEDIUM, PotentialBand.HIGH);
        };
        if (!developmentTurn || order.getFirst() == PotentialBand.HIGH) {
            return order;
        }
        List<PotentialBand> development = new ArrayList<>();
        development.add(PotentialBand.HIGH);
        order.stream().filter(b -> b != PotentialBand.HIGH).forEach(development::add);
        return List.copyOf(development);
    }

    /**
     * A chamada é vez de desenvolvimento? Só para os níveis C e D (A e B já recebem a faixa alta primeiro), em
     * {@code share}% das chamadas: {@code roll} é um número uniforme de 0 a 99 sorteado a cada chamada.
     */
    public static boolean developmentTurn(PerformanceTier tier, int share, int roll) {
        return (tier == PerformanceTier.C || tier == PerformanceTier.D) && roll < share;
    }

    // ------------------------------------------------------------------ apoio

    private static List<Member> sorted(List<Member> members) {
        List<Member> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparing(Member::userId));
        return ordered;
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
