package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactRequest;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.DenialReason;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.ReleaseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentReversalHoldsUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReverseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RS-118: corridas da retenção contra PostgreSQL real, com {@code CountDownLatch} e tempo limite — duas liberações da
 * mesma retenção (uma vence, uma auditoria), estorno de P2 em paralelo com a liberação de P1 (P2 fica pendente e o
 * porteiro nega), registro externo × estorno do mesmo devedor (o registro vê o estorno ou vem antes dele, sem
 * impasse) e carga × liberação.
 */
class ReversalHoldConcurrencyIT extends PaymentTestSupport {

    @Autowired
    ReversalHoldUseCases holds;
    @Autowired
    PaymentReversalHoldsUseCases reversalHolds;
    @Autowired
    ContactGate contactGate;

    private final Instant paidAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    private PaymentView creditorPaid(UUID titleId, String amount) {
        return asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorB, debtor,
                new BigDecimal(amount), paidAt, "PIX", "CREDITOR_DIRECT", null, null,
                new TargetInput("TITLES", null, List.of(titleId), null)), pdf(), null));
    }

    private UUID holdOf(UUID paymentId) {
        return system(() -> jdbcTemplate.queryForObject("select id from debt.reversal_hold where payment_id = ?",
                UUID.class, paymentId));
    }

    private long versionOf(UUID holdId) {
        return asFinance2(() -> reversalHolds.get(holdId)).hold().version();
    }

    /** Executa as tarefas juntas (mesma largada) e devolve o resultado ou a exceção de cada uma. */
    private List<Object> race(List<Callable<Object>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                try {
                    results.add(f.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    results.add(e.getCause());
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new AssertionError("tempo esgotado: possível impasse entre as travas", e);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoReleasesOfTheSameHoldOnlyOneWins() throws Exception {
        provision("retencao-corrida");
        PaymentView p1 = creditorPaid(titleB, "100.00");
        asFinance1(() -> payments.reverse(p1.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        UUID hold = holdOf(p1.id());
        long version = versionOf(hold);
        List<Object> results = race(List.of(
                () -> asFinance2(() -> reversalHolds.release(hold, new ReleaseCommand(version, "OTHER", "Conferido A", true))),
                () -> asAdmin(() -> reversalHolds.release(hold, new ReleaseCommand(version, "OTHER", "Conferido B", true)))));
        assertThat(results).filteredOn(r -> r instanceof Throwable).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof Throwable).first()
                .isInstanceOf(br.com.gestorcobranca.shared.domain.ConflictException.class);
        assertThat(count("select count(*) from shared.audit_event where action = 'reversal_hold.released' "
                + "and entity_id = ?", hold)).isEqualTo(1);
    }

    @Test
    void reversalOfAnotherPaymentDuringAReleaseKeepsThePairHeld() throws Exception {
        provision("retencao-p2");
        PaymentView p1 = creditorPaid(titleB, "100.00");
        PaymentView p2 = creditorPaid(titleB, "200.00");
        asFinance1(() -> payments.reverse(p1.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        UUID hold1 = holdOf(p1.id());
        long version = versionOf(hold1);
        List<Object> results = race(List.of(
                () -> asFinance2(() -> reversalHolds.release(hold1, new ReleaseCommand(version, "OTHER", "Conferido", true))),
                () -> asFinance1(() -> payments.reverse(p2.id(), new ReverseCommand("OTHER", "Credor desfez o informe")))));
        assertThat(results).noneMatch(r -> r instanceof Throwable);
        assertThat(count("select count(*) from debt.reversal_hold where id = ? and state = 'RELEASED'", hold1))
                .isEqualTo(1);
        assertThat(count("select count(*) from debt.reversal_hold where payment_id = ? and state = 'PENDING'", p2.id()))
                .isEqualTo(1);
        assertThat(system(() -> contactGate.evaluate(ContactRequest.of(debtor, creditorB, "WHATSAPP", null, null,
                "OPERATOR"))).reasons()).contains(DenialReason.PAYMENT_REVERSAL_HOLD);
    }

    @Test
    void externalRegistrationRacingAReversalSeesItOrCommitsBefore() throws Exception {
        provision("retencao-registro");
        PaymentView p1 = creditorPaid(titleB, "100.00");
        List<Object> results = race(List.of(
                () -> asFinance1(() -> payments.reverse(p1.id(), new ReverseCommand("OTHER", "Credor desfez o informe"))),
                () -> asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorB, debtor,
                        new BigDecimal("5.00"), paidAt, "PIX", "CREDITOR_REPORT", null, null,
                        new TargetInput("TITLES", null, List.of(titleB), null)), pdf(), null))));
        assertThat(results).noneMatch(r -> r instanceof Throwable);
        PaymentView p3 = (PaymentView) results.get(1);
        Instant reversedAt = system(() -> jdbcTemplate.queryForObject(
                "select reversed_at from payment.payment where id = ?", java.sql.Timestamp.class, p1.id())).toInstant();
        if ("CONFIRMED".equals(p3.status())) {
            // gravou antes do estorno: entrou sem revisão, antes da trava do estorno
            assertThat(asOperator(() -> payments.get(p3.id())).informedAt()).isBefore(reversedAt);
        } else {
            assertThat(count("select count(*) from payment.payment where id = ? "
                    + "and review_reason = 'RECENT_CREDITOR_REVERSAL'", p3.id())).isEqualTo(1);
        }
    }

    @Test
    void importRacingAReleaseBothComplete() throws Exception {
        provision("retencao-carga");
        PaymentView p1 = creditorPaid(titleB, "100.00");
        asFinance1(() -> payments.reverse(p1.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        UUID hold = holdOf(p1.id());
        long version = versionOf(hold);
        List<Object> results = race(List.of(
                () -> asFinance2(() -> reversalHolds.release(hold, new ReleaseCommand(version, "OTHER", "Conferido", true))),
                () -> system(() -> ingestion.upsertTitle(new TitleRecord(creditorB, portfolioB, debtor, "B-1", null, 1,
                        today.minusDays(20), new BigDecimal("1000.00"), null, null), Ids.newId()))));
        assertThat(results).noneMatch(r -> r instanceof Throwable);
        assertThat(count("select count(*) from debt.reversal_hold where id = ? and state = 'RELEASED'", hold))
                .isEqualTo(1);
    }
}
