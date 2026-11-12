package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.DistributionStrategy;
import br.com.gestorcobranca.collection.domain.model.PerformanceTier;
import br.com.gestorcobranca.collection.domain.model.PotentialBand;
import br.com.gestorcobranca.collection.domain.model.TierPolicy;
import br.com.gestorcobranca.collection.domain.service.CaseDistributor.Assignment;
import br.com.gestorcobranca.collection.domain.service.CaseDistributor.Candidate;
import br.com.gestorcobranca.collection.domain.service.CaseDistributor.Member;
import br.com.gestorcobranca.collection.domain.service.CaseDistributor.Plan;
import br.com.gestorcobranca.collection.domain.service.CaseDistributor.Ranked;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static br.com.gestorcobranca.collection.domain.model.PerformanceTier.A;
import static br.com.gestorcobranca.collection.domain.model.PerformanceTier.B;
import static br.com.gestorcobranca.collection.domain.model.PerformanceTier.C;
import static br.com.gestorcobranca.collection.domain.model.PerformanceTier.D;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ramo {@code TIERED} do {@link CaseDistributor} (distribuicao-por-desempenho.md §4.2 e §10-Q2). */
class TieredDistributionTest {

    static final UUID UA = new UUID(0, 1);
    static final UUID UB = new UUID(0, 2);
    static final UUID UC = new UUID(0, 3);
    static final UUID UD = new UUID(0, 4);
    static final UUID CREDITOR_1 = new UUID(1, 1);
    static final UUID CREDITOR_2 = new UUID(1, 2);
    static final TierPolicy NO_DEVELOPMENT = new TierPolicy(Map.of(A, 100, B, 100, C, 100, D, 100), 0, B);

    /** Casos com score e saldo iguais à posição (o primeiro tem o maior valor esperado). */
    static List<Candidate> ranked(int n, UUID creditor) {
        return IntStream.range(0, n).mapToObj(i -> new Candidate(new UUID(2, i), creditor, 500,
                BigDecimal.valueOf((n - i) * 100L), null)).toList();
    }

    static Member member(UUID id, PerformanceTier tier, int load, Integer capacity) {
        return new Member(id, load, capacity, true, Set.of(), tier);
    }

    static Map<UUID, Assignment> byCase(Plan plan) {
        return plan.assignments().stream().collect(Collectors.toMap(Assignment::caseId, a -> a));
    }

    static Map<UUID, Long> perUser(Plan plan) {
        return plan.assignments().stream().collect(Collectors.groupingBy(Assignment::userId, Collectors.counting()));
    }

    @Test
    void expectedValueUsesScoreProbabilityAndMeanWhenMissing() {
        // score 800 → 0,8 × 1.000 = 800; sem score → média (0,8 e 0,2 → 0,5) × 1.000 = 500
        List<Candidate> cases = List.of(
                new Candidate(new UUID(3, 1), CREDITOR_1, 800, new BigDecimal("1000"), null),
                new Candidate(new UUID(3, 2), CREDITOR_1, 200, new BigDecimal("1000"), null),
                new Candidate(new UUID(3, 3), CREDITOR_1, null, new BigDecimal("1000"), null));
        List<Ranked> ranked = CaseDistributor.rank(cases);
        assertThat(ranked).extracting(r -> r.candidate().caseId())
                .containsExactly(new UUID(3, 1), new UUID(3, 3), new UUID(3, 2));
        assertThat(ranked).extracting(Ranked::expectedValue).containsExactly(800d, 500d, 200d);
        assertThat(ranked).extracting(Ranked::band)
                .containsExactly(PotentialBand.HIGH, PotentialBand.MEDIUM, PotentialBand.LOW);
        // sem nenhum score: probabilidade 1, ordena pelo saldo
        assertThat(CaseDistributor.meanProbability(List.of(new Candidate(UUID.randomUUID(), CREDITOR_1)))).isEqualTo(1d);
    }

    @Test
    void tercilesFollowPostgresNtile() {
        // 10 casos: 4 altos, 3 médios, 3 baixos; 11: 4, 4, 3; 2: 1, 1, 0
        assertThat(IntStream.range(0, 10).mapToObj(i -> CaseDistributor.tercile(i, 10)).toList())
                .containsExactly(PotentialBand.HIGH, PotentialBand.HIGH, PotentialBand.HIGH, PotentialBand.HIGH,
                        PotentialBand.MEDIUM, PotentialBand.MEDIUM, PotentialBand.MEDIUM,
                        PotentialBand.LOW, PotentialBand.LOW, PotentialBand.LOW);
        assertThat(IntStream.range(0, 11).filter(i -> CaseDistributor.tercile(i, 11) == PotentialBand.MEDIUM).count())
                .isEqualTo(4);
        assertThat(IntStream.range(0, 2).mapToObj(i -> CaseDistributor.tercile(i, 2)).toList())
                .containsExactly(PotentialBand.HIGH, PotentialBand.MEDIUM);
    }

    @Test
    void bandsGoToPreferredTiers() {
        List<Member> members = List.of(member(UA, A, 0, 10), member(UB, B, 0, 10), member(UC, C, 0, 10),
                member(UD, D, 0, 10));
        List<Candidate> cases = ranked(9, CREDITOR_1);
        Plan plan = CaseDistributor.plan(cases, members, DistributionStrategy.TIERED, null, NO_DEVELOPMENT);
        Map<UUID, Assignment> assigned = byCase(plan);
        // altos (3 primeiros) → A; médios → B; baixos → C (o preferido de cada faixa, com capacidade)
        for (int i = 0; i < 9; i++) {
            Assignment a = assigned.get(cases.get(i).caseId());
            PotentialBand band = i < 3 ? PotentialBand.HIGH : i < 6 ? PotentialBand.MEDIUM : PotentialBand.LOW;
            assertThat(a.band()).isEqualTo(band);
            assertThat(a.userId()).isEqualTo(band == PotentialBand.HIGH ? UA : band == PotentialBand.MEDIUM ? UB : UC);
            assertThat(a.tier()).isEqualTo(band == PotentialBand.HIGH ? A : band == PotentialBand.MEDIUM ? B : C);
        }
        assertThat(plan.unassigned()).isEmpty();
    }

    @Test
    void capacityIsMultipliedByTierAndRoundedDown() {
        TierPolicy policy = TierPolicy.DEFAULT;
        assertThat(policy.effectiveCapacity(10, A)).isEqualTo(12);
        assertThat(policy.effectiveCapacity(10, B)).isEqualTo(11);
        assertThat(policy.effectiveCapacity(10, C)).isEqualTo(10);
        assertThat(policy.effectiveCapacity(10, D)).isEqualTo(8);
        assertThat(policy.effectiveCapacity(3, D)).isEqualTo(2);
        assertThat(policy.effectiveCapacity(1, D)).as("mínimo 1").isEqualTo(1);
        assertThat(policy.effectiveCapacity(null, A)).as("sem limite continua sem limite").isNull();

        List<Member> members = List.of(member(UA, A, 0, 10), member(UD, D, 0, 10));
        Plan plan = CaseDistributor.plan(ranked(30, CREDITOR_1), members, DistributionStrategy.TIERED, null,
                new TierPolicy(policy.capacityPercent(), 0, B));
        assertThat(perUser(plan)).containsEntry(UA, 12L).containsEntry(UD, 8L);
        assertThat(plan.unassigned()).hasSize(10);
    }

    @Test
    void exhaustedPreferredTierFallsToNextTierWithCapacity() {
        // só um A com capacidade 2 e um D: os altos que não cabem no A descem até o D (não há B nem C)
        List<Member> members = List.of(member(UA, A, 0, 2), member(UD, D, 0, 100));
        List<Candidate> cases = ranked(9, CREDITOR_1);
        Plan plan = CaseDistributor.plan(cases, members, DistributionStrategy.TIERED, null, NO_DEVELOPMENT);
        Map<UUID, Assignment> assigned = byCase(plan);
        assertThat(assigned.get(cases.get(0).caseId()).userId()).isEqualTo(UA);
        assertThat(assigned.get(cases.get(1).caseId()).userId()).isEqualTo(UA);
        assertThat(assigned.get(cases.get(2).caseId()).userId()).isEqualTo(UD);
        assertThat(assigned.get(cases.get(2).caseId()).band()).isEqualTo(PotentialBand.HIGH);
        assertThat(plan.unassigned()).as("nunca fica sem dono por causa do nível").isEmpty();
        // média sem B nem C: sobe ao A (se tiver capacidade) antes de descer ao D
        List<Member> withRoom = List.of(member(UA, A, 0, 100), member(UD, D, 0, 100));
        Plan medium = CaseDistributor.plan(cases, withRoom, DistributionStrategy.TIERED, null, NO_DEVELOPMENT);
        assertThat(byCase(medium).get(cases.get(4).caseId()).userId()).isEqualTo(UA);
        // baixa sem C: vai ao D
        assertThat(byCase(medium).get(cases.get(8).caseId()).userId()).isEqualTo(UD);
    }

    @Test
    void withinTierTheLeastLoadedReceives() {
        UUID a2 = new UUID(0, 9);
        List<Member> members = List.of(member(UA, A, 5, 100), member(a2, A, 0, 100));
        Plan plan = CaseDistributor.plan(ranked(3, CREDITOR_1), members, DistributionStrategy.TIERED, null,
                NO_DEVELOPMENT);
        assertThat(perUser(plan)).containsEntry(a2, 3L);
    }

    @Test
    void developmentShareRotatesHighCasesAcrossAllTiers() {
        // 60 casos → 20 altos; 15% de 20 = 3 casos altos por rodízio, espalhados pela faixa
        List<Member> members = List.of(member(UA, A, 0, 100), member(UB, B, 0, 100), member(UC, C, 0, 100),
                member(UD, D, 0, 100));
        List<Candidate> cases = ranked(60, CREDITOR_1);
        TierPolicy policy = new TierPolicy(Map.of(A, 100, B, 100, C, 100, D, 100), 15, B);
        Plan plan = CaseDistributor.plan(cases, members, DistributionStrategy.TIERED, UB, policy);
        List<Assignment> development = plan.assignments().stream()
                .filter(a -> a.band() == PotentialBand.DEVELOPMENT).toList();
        assertThat(development).hasSize(3);
        // rodízio continua depois do cursor (UB): C, D, A
        assertThat(development).extracting(Assignment::userId).containsExactly(UC, UD, UA);
        assertThat(plan.cursor()).isEqualTo(UA);
        // os demais altos continuam com o A
        assertThat(plan.assignments().stream().filter(a -> a.band() == PotentialBand.HIGH)
                .map(Assignment::userId).collect(Collectors.toSet())).containsExactly(UA);
        // espalhados: nem os 3 melhores nem os 3 piores da faixa alta
        List<UUID> highIds = cases.subList(0, 20).stream().map(Candidate::caseId).toList();
        List<Integer> positions = development.stream().map(a -> highIds.indexOf(a.caseId())).toList();
        assertThat(positions).doesNotContain(0).allMatch(p -> p >= 0 && p < 20);
        assertThat(positions.get(2) - positions.get(0)).isGreaterThan(5);
    }

    @Test
    void withoutPublishedTierTheDefaultTierApplies() {
        // sem nível publicado = padrão B (recebe a faixa média primeiro)
        List<Member> members = List.of(member(UA, A, 0, 100), member(UB, null, 0, 100));
        List<Candidate> cases = ranked(3, CREDITOR_1);
        Plan plan = CaseDistributor.plan(cases, members, DistributionStrategy.TIERED, null, NO_DEVELOPMENT);
        Assignment medium = byCase(plan).get(cases.get(1).caseId());
        assertThat(medium.userId()).isEqualTo(UB);
        assertThat(medium.tier()).isEqualTo(B);
        // padrão D: a média sobe ao A antes de descer ao D
        TierPolicy defaultD = new TierPolicy(NO_DEVELOPMENT.capacityPercent(), 0, D);
        Plan withD = CaseDistributor.plan(cases, members, DistributionStrategy.TIERED, null, defaultD);
        assertThat(byCase(withD).get(cases.get(1).caseId()).userId()).isEqualTo(UA);
        assertThat(byCase(withD).get(cases.get(2).caseId()).tier()).isEqualTo(D);
    }

    @Test
    void creditorScopeStillApplies() {
        Member onlyCreditor2 = new Member(UA, 0, 100, false, Set.of(CREDITOR_2), A);
        Member restricted = new Member(UB, 0, 100, false, Set.of(CREDITOR_1), D);
        List<Candidate> cases = new ArrayList<>(ranked(3, CREDITOR_1));
        cases.add(new Candidate(new UUID(4, 1), UUID.randomUUID(), 900, new BigDecimal("99999"), null));
        Plan plan = CaseDistributor.plan(cases, List.of(onlyCreditor2, restricted), DistributionStrategy.TIERED, null,
                NO_DEVELOPMENT);
        // casos do credor 1 vão ao D (o A não alcança o credor); o de outro credor fica sem dono
        assertThat(plan.assignments()).allMatch(a -> a.userId().equals(UB));
        assertThat(plan.unassigned()).containsExactly(new UUID(4, 1));
    }

    @Test
    void precomputedBandsFromTheWholeQueueAreKept() {
        List<Candidate> cases = List.of(new Candidate(new UUID(5, 1), CREDITOR_1, 100, BigDecimal.TEN, PotentialBand.LOW),
                new Candidate(new UUID(5, 2), CREDITOR_1, 100, BigDecimal.ONE, PotentialBand.LOW));
        Plan plan = CaseDistributor.plan(cases, List.of(member(UA, A, 0, 100), member(UC, C, 0, 100)),
                DistributionStrategy.TIERED, null, NO_DEVELOPMENT);
        assertThat(plan.assignments()).allMatch(a -> a.band() == PotentialBand.LOW && a.userId().equals(UC));
    }

    @Test
    void nextCaseBandOrderAndDevelopmentTurn() {
        assertThat(CaseDistributor.nextCaseBands(A, false)).containsExactly(PotentialBand.HIGH, PotentialBand.MEDIUM,
                PotentialBand.LOW);
        assertThat(CaseDistributor.nextCaseBands(C, false)).containsExactly(PotentialBand.MEDIUM, PotentialBand.LOW,
                PotentialBand.HIGH);
        assertThat(CaseDistributor.nextCaseBands(D, false)).containsExactly(PotentialBand.LOW, PotentialBand.MEDIUM,
                PotentialBand.HIGH);
        assertThat(CaseDistributor.nextCaseBands(D, true)).containsExactly(PotentialBand.HIGH, PotentialBand.LOW,
                PotentialBand.MEDIUM);
        assertThat(CaseDistributor.developmentTurn(C, 15, 14)).isTrue();
        assertThat(CaseDistributor.developmentTurn(C, 15, 15)).isFalse();
        assertThat(CaseDistributor.developmentTurn(A, 50, 0)).as("A e B já recebem a faixa alta").isFalse();
        assertThat(CaseDistributor.developmentTurn(D, 0, 0)).isFalse();
    }

    @Test
    void policyLimitsAreValidated() {
        assertThatThrownBy(() -> new TierPolicy(Map.of(A, 201, B, 100, C, 100, D, 100), 15, B))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new TierPolicy(Map.of(A, 100, B, 100, C, 100, D, 49), 15, B))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new TierPolicy(Map.of(A, 100, B, 100, C, 100), 15, B))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new TierPolicy(Map.of(A, 100, B, 100, C, 100, D, 100), 51, B))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(TierPolicy.DEFAULT.capacityPercent()).containsEntry(A, 120).containsEntry(D, 80);
        assertThat(TierPolicy.DEFAULT.developmentShare()).isEqualTo(15);
        assertThat(TierPolicy.DEFAULT.defaultTier()).isEqualTo(B);
    }
}
