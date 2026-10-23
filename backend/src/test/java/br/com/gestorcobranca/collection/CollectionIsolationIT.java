package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseFilter;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.AssignCommand;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.DistributionResult;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.RedistributeCommand;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueSummary;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueView;
import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases.TabulationCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isolamento e concorrência: RLS entre assessorias, escopo por credor (404/403 e listagens),
 * histórico imutável no banco, "próximo caso" concorrente sem entrega duplicada, distribuição em
 * rodízio e redistribuição.
 */
class CollectionIsolationIT extends CollectionTestSupport {

    static final String[] CPFS = {"543.231.948-97", "757.491.186-06", "252.760.189-87", "555.979.711-15",
            "471.049.746-01", "507.529.170-80", "342.366.712-55", "768.426.846-50", "563.212.233-60", "079.244.026-91",
            "859.952.890-47", "786.666.176-67"};

    QueueView queueA;

    @BeforeEach
    void setUp() {
        provision("iso");
        queueA = asManager(() -> queues.create(queue("BANCO_A", 10, creditorA, "ROUND_ROBIN", null, Set.of(team))));
    }

    private List<UUID> openCases(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID debtor = debtor(CPFS[i]);
            load(creditorA, portfolioA, debtor, "1" + i + "0.00");
            ids.add(activeCase(debtor, portfolioA).id());
        }
        awaitIdle("CollectionEventListeners");
        return ids;
    }

    @Test
    void concurrentNextCaseNeverDeliversTheSameCaseTwice() throws Exception {
        List<UUID> opened = openCases(6);
        // dois operadores da equipe pedindo casos em paralelo, 4 pedidos cada (8 pedidos para 6 casos)
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<CaseView>>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID operator = i % 2 == 0 ? operatorX : operatorY;
            futures.add(pool.submit(() -> {
                start.await();
                return asOperator(operator, () -> work.nextCase(queueA.id()));
            }));
        }
        start.countDown();
        List<UUID> delivered = new ArrayList<>();
        for (Future<Optional<CaseView>> f : futures) {
            f.get(30, TimeUnit.SECONDS).ifPresent(c -> delivered.add(c.id()));
        }
        pool.shutdown();
        assertThat(delivered).doesNotHaveDuplicates().hasSize(6).containsExactlyInAnyOrderElementsOf(opened);
        assertThat(count("select count(*) from collection.collection_case where queue_id = ? and assignee_user_id is null",
                queueA.id())).isZero();
        // cada caso tem exatamente um período vigente (o do operador que o recebeu)
        assertThat(count("select count(*) from collection.case_assignment where valid_to is null and mode = 'NEXT_CASE'"))
                .isEqualTo(6);
        QueueSummary summary = asSupervisor(() -> queues.summary(queueA.id()));
        assertThat(summary.available()).isZero();
        assertThat(summary.assigned()).isEqualTo(6);
    }

    @Test
    void roundRobinDistributionAndRedistribution() {
        openCases(4);
        DistributionResult result = asSupervisor(() -> work.distribute(queueA.id(), 100));
        assertThat(result.assigned()).isEqualTo(4);
        assertThat(result.assignments()).extracting(a -> a.userId()).containsOnly(operatorX, operatorY);
        assertThat(result.assignments().stream().filter(a -> a.userId().equals(operatorX)).count()).isEqualTo(2);
        assertThat(count("select count(*) from shared.business_event where event_type = 'case.assigned' "
                + "and attributes ->> 'mode' = 'DISTRIBUTION'")).isEqualTo(4);

        // redistribuição dos casos de X para Y
        DistributionResult moved = asSupervisor(() -> work.redistribute(new RedistributeCommand(operatorX, Set.of(operatorY),
                "Férias")));
        assertThat(moved.assigned()).isEqualTo(2);
        assertThat(asOperator(operatorY, () -> work.myCases(PageQuery.of(0, 50))).totalElements()).isEqualTo(4);
        // operador do outro credor não é destino válido de caso do credor A
        UUID caseId = moved.assignments().getFirst().caseId();
        assertThatThrownBy(() -> asSupervisor(() -> work.assign(caseId, new AssignCommand(operatorZ, null, "x"))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("case.assignee_out_of_scope");
        // devolução às filas
        DistributionResult released = asSupervisor(() -> work.redistribute(new RedistributeCommand(operatorY, Set.of(),
                "Desligamento")));
        assertThat(released.unassigned()).isEqualTo(4);
        assertThat(count("select count(*) from collection.collection_case where assignee_user_id is not null")).isZero();
        assertThat(asSupervisor(() -> work.assignmentHistory(caseId, PageQuery.of(0, 50))).content())
                .extracting(a -> a.mode()).contains("OPENED", "DISTRIBUTION", "REDISTRIBUTION");
    }

    @Test
    void pullQueueRejectsDistributionAndCapacityLimitsNextCase() {
        QueueView pull = asManager(() -> queues.create(queue("PULL_B", 5, creditorB, "PULL", 1, Set.of())));
        assertThatThrownBy(() -> asSupervisor(() -> work.distribute(pull.id(), 10)))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("queue.pull_only");
        UUID d1 = debtor(CPFS[0]);
        UUID d2 = debtor(CPFS[1]);
        load(creditorB, portfolioB, d1, "50.00");
        load(creditorB, portfolioB, d2, "60.00");
        awaitIdle("CollectionEventListeners");
        assertThat(asOperator(operatorZ, () -> work.nextCase(pull.id()))).isPresent();
        assertThatThrownBy(() -> asOperator(operatorZ, () -> work.nextCase(pull.id())))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("queue.capacity_reached");
        // Z não atende a fila do credor A (escopo) e não a enxerga no próximo caso geral
        assertThat(asOperator(operatorZ, () -> work.nextCase(null))).isEmpty();
    }

    @Test
    void creditorScopeHidesAndForbids() {
        List<UUID> opened = openCases(1);
        UUID caseA = opened.getFirst();
        assertThatThrownBy(() -> asOperator(operatorZ, () -> cases.get(caseA))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asOperator(operatorZ, () -> work.takeOver(caseA, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(asOperator(operatorZ, () -> cases.search(new CaseFilter(null, null, null, null, null, null, null, null),
                PageQuery.of(0, 50))).totalElements()).isZero();
        assertThat(asOperator(operatorX, () -> cases.search(new CaseFilter(creditorA, null, null, Set.of("OPEN"), null,
                null, null, true), PageQuery.of(0, 50))).totalElements()).isEqualTo(1);
        assertThat(count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED' "
                + "and details ->> 'resource' = 'collection_case'")).isGreaterThanOrEqualTo(1);
        // configuração global exige acesso a todos os credores
        assertThatThrownBy(() -> as(operatorZ, "GESTOR", br.com.gestorcobranca.shared.security.CreditorScope.of(
                Set.of(creditorB)), () -> tabulations.create(new TabulationCommand("NEW_ONE", "Nova", false, false, false,
                false, 1)))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> as(operatorZ, "GESTOR", br.com.gestorcobranca.shared.security.CreditorScope.of(
                Set.of(creditorB)), () -> queues.create(queue("ANY", 1, creditorA, "PULL", null, Set.of()))))
                .isInstanceOf(ScopeAccessDeniedException.class);
    }

    @Test
    void rowLevelSecurityAndImmutableHistory() {
        UUID caseId = openCases(1).getFirst();
        record(operatorX, caseId, interaction("NO_ANSWER", null, null, null));
        UUID otherTenant = tenant;
        UUID otherCase = caseId;
        provision("iso2");
        // outra assessoria não enxerga casos, interações, histórico nem filas da primeira
        assertThat(count("select count(*) from collection.collection_case")).isZero();
        assertThat(count("select count(*) from collection.interaction")).isZero();
        assertThat(count("select count(*) from collection.case_assignment")).isZero();
        assertThat(count("select count(*) from collection.queue")).isZero();
        assertThatThrownBy(() -> system(() -> cases.get(otherCase))).isInstanceOf(ResourceNotFoundException.class);
        // escrita com tenant de outra assessoria é recusada pelo RLS
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("""
                insert into collection.tabulation (id, tenant_id, version, created_at, updated_at, code, name,
                    effective_contact, promise, callback, requires_note, display_order, active)
                values (?, ?, 0, now(), now(), 'INTRUSO', 'Intruso', false, false, false, false, 1, true)
                """, Ids.newId(), otherTenant))).isInstanceOf(DataAccessException.class);
        // sem tenant, nenhuma linha visível
        assertThat(jdbcTemplate.queryForObject("select count(*) from collection.collection_case", Long.class)).isZero();

        tenant = otherTenant;
        // interações são append-only; atribuição só recebe o fim da vigência, uma vez
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update collection.interaction set outcome_code = 'X' where case_id = ?", caseId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "delete from collection.interaction where case_id = ?", caseId))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update collection.case_assignment set user_id = null where case_id = ?", caseId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update collection.case_assignment set valid_to = now() where case_id = ? and valid_to is not null",
                caseId))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "delete from collection.collection_case where id = ?", caseId))).isInstanceOf(DataAccessException.class);
        Set<String> modes = new HashSet<>(system(() -> jdbcTemplate.queryForList(
                "select mode from collection.case_assignment where case_id = ?", String.class, caseId)));
        assertThat(modes).containsExactlyInAnyOrder("OPENED", "INTERACTION");
    }
}
