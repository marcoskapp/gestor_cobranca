package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Regras únicas de elegibilidade usadas pela lista de usuários atribuíveis e pela gravação. */
class AssigneeRulesTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private static UserSummary user(boolean active, boolean all, Set<UUID> creditors, String... permissions) {
        return new UserSummary(UUID.randomUUID(), "Fulano", active, false, all, creditors, Set.of(permissions), Set.of());
    }

    @Test
    void caseWorkerNeedsActiveCaseWorkAndCreditor() {
        assertThat(CaseWorkers.canWork(user(true, false, Set.of(A), "CASE_WORK"), A)).isTrue();
        assertThat(CaseWorkers.canWork(user(true, true, Set.of(), "CASE_WORK"), A)).isTrue();
        assertThat(CaseWorkers.canWork(user(true, false, Set.of(B), "CASE_WORK"), A)).as("fora do credor").isFalse();
        assertThat(CaseWorkers.canWork(user(false, true, Set.of(), "CASE_WORK"), A)).as("inativo").isFalse();
        assertThat(CaseWorkers.canWork(user(true, true, Set.of(), "CASE_VIEW"), A)).as("sem CASE_WORK").isFalse();
        assertThat(CaseWorkers.canWork(null, A)).isFalse();
    }

    @Test
    void redistributionTargetReachesTheActorScopeAndIsNotTheSource() {
        UserSummary onlyB = user(true, false, Set.of(B), "CASE_WORK");
        UserSummary all = user(true, true, Set.of(), "CASE_WORK");
        UserSummary none = user(true, false, Set.of(), "CASE_WORK");
        assertThat(CaseWorkers.canReceiveRedistribution(onlyB, null, null)).isTrue();
        assertThat(CaseWorkers.canReceiveRedistribution(onlyB, null, Set.of(B))).isTrue();
        assertThat(CaseWorkers.canReceiveRedistribution(onlyB, null, Set.of(A))).as("sem credor em comum").isFalse();
        assertThat(CaseWorkers.canReceiveRedistribution(all, null, Set.of(A))).isTrue();
        assertThat(CaseWorkers.canReceiveRedistribution(all, all.id(), null)).as("a própria origem").isFalse();
        assertThat(CaseWorkers.canReceiveRedistribution(none, null, null)).as("sem nenhum credor").isFalse();
        assertThat(CaseWorkers.canReceiveRedistribution(user(false, true, Set.of(), "CASE_WORK"), null, null)).isFalse();
        assertThat(CaseWorkers.canReceiveRedistribution(user(true, true, Set.of(), "INBOX_VIEW"), null, null)).isFalse();
    }

    @Test
    void disputeAnalystRulesForAssignmentPoolAndRedistribution() {
        UserSummary worksB = user(true, false, Set.of(B), "DISPUTE_WORK");
        UserSummary manager = user(true, true, Set.of(), "DISPUTE_MANAGE");
        assertThat(DisputeAssignments.isEligible(worksB, B)).isTrue();
        assertThat(DisputeAssignments.isEligible(worksB, A)).isFalse();
        assertThat(DisputeAssignments.isEligible(user(true, true, Set.of(), "CASE_WORK"), A)).isFalse();
        // grupo geral (sem credor): basta trabalhar contestações
        assertThat(DisputeAssignments.isEligibleForPool(worksB, null)).isTrue();
        assertThat(DisputeAssignments.isEligibleForPool(worksB, A)).isFalse();
        assertThat(DisputeAssignments.isEligibleForPool(null, null)).isFalse();
        assertThat(DisputeAssignments.isEligibleForPool(user(false, true, Set.of(), "DISPUTE_WORK"), null)).isFalse();
        // destino da redistribuição: todos os credores das contestações da origem
        assertThat(DisputeAssignments.isEligibleForAll(worksB, Set.of(B))).isTrue();
        assertThat(DisputeAssignments.isEligibleForAll(worksB, Set.of(A, B))).isFalse();
        assertThat(DisputeAssignments.isEligibleForAll(worksB, Set.of())).isTrue();
        assertThat(DisputeAssignments.isEligibleForAll(manager, Set.of(A, B))).isTrue();
    }
}
