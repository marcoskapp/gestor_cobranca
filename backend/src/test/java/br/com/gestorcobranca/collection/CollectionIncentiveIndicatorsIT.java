package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.CallbackInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.PromiseInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordResult;
import br.com.gestorcobranca.collection.application.port.in.PromiseUseCases.PromiseView;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases.TaskView;
import br.com.gestorcobranca.collection.application.service.CollectionTestHooks;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentApplication;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries.IndicatorQuery;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries.IndicatorReport;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries.IndicatorRow;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.collection.CallbackStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ponta a ponta collection → incentive: retornos e promessas operados pelos serviços reais do
 * collection (interação, tarefa, job de vencimento, pagamento confirmado, job de quebra) chegam ao
 * incentive por {@code CallbackStatusChanged}/{@code PromiseStatusChanged} e aparecem nos
 * indicadores "retornos cumpridos" e "promessas cumpridas" (§2 de remuneracao-variavel.md); o saldo
 * elegível atribuído acompanha mudanças só de saldo ({@code CaseBalanceChanged}).
 */
class CollectionIncentiveIndicatorsIT extends CollectionTestSupport {

    @Autowired
    PerformanceQueries performance;

    @BeforeEach
    void setUp() {
        provision("cinc");
        asManager(() -> queues.create(queue("BANCO_A", 10, creditorA, "PULL", null, Set.of(team))));
        // promessa sem tolerância; retorno com 15 minutos de tolerância
        system(() -> settings.update(new SettingsCommand(0, 30, 15, 10)));
    }

    @Test
    void callbacksAndPromisesFeedTheNegotiatorIndicators() {
        UUID debtor = debtor("526.018.159-06");
        List<UUID> titles = load(creditorA, portfolioA, debtor, "300.00", "200.00");
        CaseView c = asOperator(operatorX, () -> work.nextCase(null)).orElseThrow();
        assertThat(c.assigneeUserId()).isEqualTo(operatorX);

        // A: agendado, atrasado pelo job, cumprido depois (com atraso: conta como atrasado)
        RecordResult a = record(operatorX, c.id(), interaction("CALLBACK", null,
                new CallbackInput(LocalDateTime.now(ZONE).plusHours(2).withNano(0), null, null), null));
        clock.advance(Duration.ofHours(3));
        assertThat(system(() -> CollectionTestHooks.markMissedCallbacks(applicationContext))).isEqualTo(1);
        record(operatorX, c.id(), interaction("NO_ANSWER", null, null, null));
        assertThat(task(c.id(), a.callback().id()).completedOnTime()).isFalse();
        clock.reset();

        // B: cumprido dentro da tolerância
        RecordResult b = record(operatorX, c.id(), interaction("CALLBACK", null,
                new CallbackInput(LocalDateTime.now(ZONE).plusMinutes(10).withNano(0), null, null), null));
        record(operatorX, c.id(), interaction("NO_ANSWER", null, null, null));
        assertThat(task(c.id(), b.callback().id()).completedOnTime()).isTrue();

        // C: cancelado antes de vencer (sai do denominador)
        RecordResult cancelled = record(operatorX, c.id(), interaction("CALLBACK", null,
                new CallbackInput(LocalDateTime.now(ZONE).plusHours(1).withNano(0), null, null), null));
        asOperator(operatorX, () -> tasks.cancel(cancelled.callback().id(), "Devedor pediu contato por e-mail"));

        // D: reagendado antes de vencer (o primeiro horário é substituído) e cumprido no novo horário
        RecordResult d = record(operatorX, c.id(), interaction("CALLBACK", null,
                new CallbackInput(LocalDateTime.now(ZONE).plusHours(1).withNano(0), null, null), null));
        asOperator(operatorX, () -> tasks.reschedule(d.callback().id(), LocalDateTime.now(ZONE).plusMinutes(5).withNano(0)));
        record(operatorX, c.id(), interaction("NO_ANSWER", null, null, null));
        assertThat(task(c.id(), d.callback().id()).completedOnTime()).isTrue();

        // promessa 1: cumprida por pagamento confirmado sem acordo
        LocalDate due = LocalDate.now(ZONE).plusDays(3);
        RecordResult promised = record(operatorX, c.id(), interaction("PROMISE",
                new PromiseInput(new BigDecimal("300.00"), due), null, "Paga na sexta"));
        UUID paymentId = Ids.newId();
        Instant paidAt = Instant.now();
        system(() -> ledger.applyPayment(new PaymentApplication(titles.getFirst(), paymentId,
                ComponentAmounts.principalOnly(new BigDecimal("300.00")), paidAt)));
        publishAndAwait(new PaymentConfirmed(Ids.newId(), tenant, paidAt, paymentId, debtor, creditorA, portfolioA, null,
                new BigDecimal("300.00"), paidAt, "EXTERNAL", "PIX",
                List.of(new PaymentConfirmed.Allocation(titles.getFirst(), null, new BigDecimal("300.00")))),
                "collection.payment-confirmed");
        assertThat(system(() -> promises.get(promised.promise().id())).status()).isEqualTo("KEPT");

        // promessa 2 (outro caso): quebrada pelo job depois do vencimento
        UUID debtor2 = debtor("083.016.613-05");
        load(creditorA, portfolioA, debtor2, "400.00");
        CaseView c2 = activeCase(debtor2, portfolioA);
        RecordResult promised2 = record(operatorX, c2.id(), interaction("PROMISE",
                new PromiseInput(new BigDecimal("150.00"), LocalDate.now(ZONE).plusDays(1)), null, null));
        clock.advance(Duration.ofDays(3));
        assertThat(system(() -> CollectionTestHooks.breakPromises(applicationContext))).isEqualTo(1);
        clock.reset();
        PromiseView broken = system(() -> promises.get(promised2.promise().id()));
        assertThat(broken.status()).isEqualTo("BROKEN");

        drainJobs();
        awaitIdle("CollectionEventListeners");
        awaitIdle("IncentiveEventListeners");

        IndicatorRow x = rowFor(operatorX);
        assertThat(x.effort().callbacksScheduled()).isEqualTo(3);
        assertThat(x.effort().callbacksKept()).isEqualTo(2);
        assertThat(x.effort().callbacksMissed()).isEqualTo(1);
        assertThat(x.effort().callbacksKeptRate()).isEqualByComparingTo("0.6667");
        assertThat(x.conversion().promisesRegistered()).isEqualTo(2);
        assertThat(x.conversion().promisesDue()).isEqualTo(2);
        assertThat(x.conversion().promisesKept()).isEqualTo(1);
        assertThat(x.conversion().promisesBroken()).isEqualTo(1);
        assertThat(x.conversion().promisesKeptRate()).isEqualByComparingTo("0.5000");
        assertThat(report(null).unavailableMetrics())
                .doesNotContain("effort.callbacksKeptRate", "conversion.promisesKeptRate");
        // situações materializadas: reagendamento substituiu o primeiro horário de D; C cancelado
        assertThat(count("select count(*) from incentive.callback_commitment where task_id = ? and status = 'SUPERSEDED'",
                d.callback().id())).isEqualTo(1);
        assertThat(count("select count(*) from incentive.callback_commitment where task_id = ? and status = 'CANCELLED'",
                cancelled.callback().id())).isEqualTo(1);

        // denominador da taxa de recuperação: mudança só de saldo (nova carga, mesmo status e dono)
        // chega por CaseBalanceChanged e atualiza o saldo elegível atribuído a X
        load(creditorA, portfolioA, debtor, "100.00");
        CaseView afterLoad = activeCase(debtor, portfolioA);
        assertThat(afterLoad.assigneeUserId()).isEqualTo(operatorX);
        awaitIdle("IncentiveEventListeners");
        assertThat(count("select count(*) from shared.consumed_event where consumer = 'incentive.case-balance' "
                + "and tenant_id = ?", tenant)).isGreaterThanOrEqualTo(1);
        BigDecimal snapshot = system(() -> jdbcTemplate.queryForObject("""
                select eligible_balance from incentive.case_snapshot where case_id = ?
                order by effective_at desc limit 1
                """, BigDecimal.class, c.id()));
        assertThat(snapshot).isEqualByComparingTo(afterLoad.eligibleBalance());
        assertThat(afterLoad.eligibleBalance()).isEqualByComparingTo("300.00");
    }

    @Test
    void outOfOrderAndRedeliveredCallbackEventsGiveTheSameIndicators() {
        UUID taskId = Ids.newId();
        UUID caseId = Ids.newId();
        Instant dueAt = LocalDate.now(ZONE).atTime(15, 0).atZone(ZONE).toInstant();
        Instant scheduledAt = dueAt.minus(Duration.ofHours(3));
        CallbackStatusChanged kept = callbackEvent(taskId, caseId, dueAt, CallbackStatusChanged.KEPT, dueAt.plusSeconds(60));
        CallbackStatusChanged scheduled = callbackEvent(taskId, caseId, dueAt, CallbackStatusChanged.SCHEDULED, scheduledAt);
        // o desfecho chega antes do agendamento; reentrega do mesmo evento não duplica
        publishAndAwait(kept, "incentive.callback-status");
        publishAndAwait(scheduled, "incentive.callback-status");
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(kept));
        awaitIdle("IncentiveEventListeners");

        IndicatorRow y = rowFor(operatorY);
        assertThat(y.effort().callbacksScheduled()).isEqualTo(1);
        assertThat(y.effort().callbacksKept()).isEqualTo(1);
        assertThat(y.effort().callbacksKeptRate()).isEqualByComparingTo("1.0000");
    }

    private CallbackStatusChanged callbackEvent(UUID taskId, UUID caseId, Instant dueAt, String status, Instant at) {
        return new CallbackStatusChanged(Ids.newId(), tenant, at, taskId, caseId, Ids.newId(), creditorA, portfolioA,
                operatorY, team, dueAt, dueAt.plus(Duration.ofMinutes(15)), status,
                CallbackStatusChanged.KEPT.equals(status) ? operatorY : null);
    }

    private TaskView task(UUID caseId, UUID taskId) {
        return system(() -> tasks.listByCase(caseId)).stream().filter(t -> t.id().equals(taskId)).findFirst()
                .orElseThrow();
    }

    private IndicatorReport report(UUID userId) {
        LocalDate today = LocalDate.now(ZONE);
        return asManager(() -> performance.indicators(new IndicatorQuery(today.minusDays(1), today.plusDays(5), userId,
                null, null, null, null, null, "USER")));
    }

    private IndicatorRow rowFor(UUID userId) {
        return report(userId).rows().stream().filter(r -> r.groupKey().equals(userId.toString())).findFirst()
                .orElseThrow(() -> new AssertionError("sem indicadores para " + userId));
    }
}
