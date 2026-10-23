package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.DistributionStrategy;
import br.com.gestorcobranca.collection.domain.service.CaseDistributor.Candidate;
import br.com.gestorcobranca.collection.domain.service.CaseDistributor.Member;
import br.com.gestorcobranca.collection.domain.service.CaseDistributor.Plan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseDistributorTest {

    static final UUID A = new UUID(0, 1);
    static final UUID B = new UUID(0, 2);
    static final UUID C = new UUID(0, 3);
    static final UUID CREDITOR_1 = UUID.randomUUID();
    static final UUID CREDITOR_2 = UUID.randomUUID();

    static List<Candidate> cases(int n, UUID creditor) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> new Candidate(UUID.randomUUID(), creditor)).toList();
    }

    static Map<UUID, Long> perUser(Plan plan) {
        return plan.assignments().stream().collect(Collectors.groupingBy(CaseDistributor.Assignment::userId,
                Collectors.counting()));
    }

    @Test
    void roundRobinContinuesAfterTheCursor() {
        List<Member> members = List.of(new Member(C, 0, null, true, Set.of()), new Member(A, 0, null, true, Set.of()),
                new Member(B, 0, null, true, Set.of()));
        Plan plan = CaseDistributor.plan(cases(4, CREDITOR_1), members, DistributionStrategy.ROUND_ROBIN, A);
        assertThat(plan.assignments()).extracting(CaseDistributor.Assignment::userId).containsExactly(B, C, A, B);
        assertThat(plan.cursor()).isEqualTo(B);
        assertThat(plan.unassigned()).isEmpty();
    }

    @Test
    void leastLoadedBalancesWithCurrentLoadAndCapacity() {
        List<Member> members = List.of(new Member(A, 3, 4, true, Set.of()), new Member(B, 0, 4, true, Set.of()),
                new Member(C, 1, 4, true, Set.of()));
        Plan plan = CaseDistributor.plan(cases(8, CREDITOR_1), members, DistributionStrategy.LEAST_LOADED, null);
        Map<UUID, Long> counts = perUser(plan);
        assertThat(counts.getOrDefault(A, 0L) + 3).isLessThanOrEqualTo(4);
        assertThat(counts.get(B)).isEqualTo(4);
        assertThat(counts.get(C)).isEqualTo(3);
        assertThat(plan.unassigned()).isEmpty();
        Plan full = CaseDistributor.plan(cases(10, CREDITOR_1), members, DistributionStrategy.LEAST_LOADED, null);
        assertThat(full.assignments()).hasSize(8);
        assertThat(full.unassigned()).hasSize(2);
    }

    @Test
    void respectsCreditorScopeOfEachMember() {
        List<Member> members = List.of(new Member(A, 0, null, false, Set.of(CREDITOR_1)),
                new Member(B, 0, null, false, Set.of(CREDITOR_2)));
        List<Candidate> mixed = new java.util.ArrayList<>(cases(2, CREDITOR_1));
        mixed.addAll(cases(2, CREDITOR_2));
        mixed.add(new Candidate(UUID.randomUUID(), UUID.randomUUID()));
        Plan plan = CaseDistributor.plan(mixed, members, DistributionStrategy.ROUND_ROBIN, null);
        Map<UUID, UUID> creditorOfCase = mixed.stream().collect(Collectors.toMap(Candidate::caseId, Candidate::creditorId));
        Map<UUID, UUID> userOfCase = plan.assignments().stream().collect(Collectors.toMap(CaseDistributor.Assignment::caseId,
                CaseDistributor.Assignment::userId));
        userOfCase.forEach((caseId, user) -> assertThat(creditorOfCase.get(caseId))
                .isEqualTo(user.equals(A) ? CREDITOR_1 : CREDITOR_2));
        assertThat(plan.unassigned()).hasSize(1);
        assertThat(perUser(plan).values()).containsOnly(2L);
    }

    @Test
    void pullQueuesAreNotDistributed() {
        assertThatThrownBy(() -> CaseDistributor.plan(List.of(), List.of(), DistributionStrategy.PULL, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CaseDistributor.plan(cases(2, CREDITOR_1), List.of(), DistributionStrategy.LEAST_LOADED, null)
                .unassigned()).hasSize(2);
    }
}
