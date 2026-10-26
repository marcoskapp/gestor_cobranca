package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementBreakSuspensionUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementBreakSuspensionUseCases.BreakSuspensionView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.negotiation.application.service.NegotiationTestHooks;
import br.com.gestorcobranca.payment.application.port.in.PaymentReviewStatusUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Quebra do acordo suspensa enquanto há pagamento externo em revisão (decisão do responsável de
 * 26/10/2026; decisões 480–489): suspende no job, a aprovação baixa a parcela e encerra a suspensão, a
 * recusa devolve a quebra depois da carência (e a mesma parcela não suspende de novo), o prazo máximo
 * libera a quebra e destaca a pendência para quem revisa, com isolamento por assessoria e credor.
 * <p>
 * Regra de quebra da política de mercado: 2 parcelas em atraso há mais de 5 dias. O dia da quebra
 * ({@code breakDay}) é o 6º dia após o vencimento da 2ª parcela. Padrões: prazo máximo 7 dias, carência 3.
 */
class PaymentBreakSuspensionIT extends PaymentTestSupport {

    private static final int MAX_DAYS = 7;
    private static final int GRACE_DAYS = 3;

    @Autowired
    AgreementBreakSuspensionUseCases breakSuspensions;
    @Autowired
    PaymentReviewStatusUseCases reviewStatus;
    @Autowired
    PaymentSettingsUseCases settings;
    @Autowired
    PendingWorkUseCases pendingWork;

    private final Instant paidAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    private AgreementView agreement;
    private LocalDate breakDay;

    private void scenario(String prefix) {
        provision(prefix);
        asAdmin(() -> settings.update(new SettingsCommand(new BigDecimal("50.00"), 86_400, 30, 3)));
        agreement = formalizeAgreement(3);
        breakDay = agreement.installments().get(1).dueDate().plusDays(6);
    }

    private PaymentView inform(BigDecimal amount, String reference) {
        PaymentView p = asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorA, debtor, amount,
                paidAt, "TRANSFER", "AGENCY_ACCOUNT", reference, null, new TargetInput("AGREEMENT", agreement.id(),
                List.of(), null)), pdf(), null));
        assertThat(p.status()).isEqualTo("INFORMED");
        return p;
    }

    private void daily(LocalDate day) {
        system(() -> {
            NegotiationTestHooks.daily(applicationContext, day);
            return null;
        });
    }

    private String status() {
        return agreement(agreement.id()).status();
    }

    private BreakSuspensionView suspension() {
        List<BreakSuspensionView> found = asOperator(() -> breakSuspensions.latest(List.of(agreement.id())));
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private long businessEvents(String type) {
        return count("select count(*) from shared.business_event where event_type = ? and reference_id = ?", type,
                agreement.id());
    }

    private long audits(String action) {
        return count("select count(*) from shared.audit_event where action = ? and entity_id = ?", action, agreement.id());
    }

    private BigDecimal first() {
        return agreement.installments().getFirst().amount();
    }

    @Test
    void breakIsSuspendedWhileInReviewAndApprovalSettlesTheInstallment() {
        scenario("suspensao");
        // externo de outro destino (título do credor B) não segura a quebra deste acordo
        asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorB, debtor, new BigDecimal("80.00"),
                paidAt, "CASH", "CREDITOR_DIRECT", null, null, new TargetInput("TITLES", null, List.of(titleB), null)),
                pdf(), null));
        PaymentView pending = inform(first(), "DEP-1");
        assertThat(asOperator(() -> breakSuspensions.latest(List.of(agreement.id())))).isEmpty();

        // dia da quebra: a regra é atingida, mas há pagamento em revisão → suspende
        daily(breakDay);
        assertThat(status()).isEqualTo("FORMALIZED");
        BreakSuspensionView active = suspension();
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.startedOn()).isEqualTo(breakDay);
        assertThat(active.suspendedUntil()).isEqualTo(breakDay.plusDays(MAX_DAYS));
        assertThat(active.pendingPayments()).isEqualTo(1);
        assertThat(active.pendingAmount()).isEqualByComparingTo(first());
        awaitTrue("evento de negócio da suspensão", () -> businessEvents("agreement.break_suspended") == 1);
        assertThat(audits("agreement.break_suspended")).isEqualTo(1);
        assertThat(agreement(agreement.id()).installments()).extracting(InstallmentView::status)
                .containsExactly("OVERDUE", "OVERDUE", "OPEN");

        // quem negocia vê a suspensão e o prazo no resumo de revisão; quem revisa, na fila
        PaymentReviewStatusUseCases.AgreementReviewStatus review = asOperator(() -> reviewStatus.forAgreement(agreement.id()));
        assertThat(review.breakSuspension().maxDays()).isEqualTo(MAX_DAYS);
        assertThat(review.breakSuspension().graceDays()).isEqualTo(GRACE_DAYS);
        assertThat(review.breakSuspension().suspensions()).extracting(BreakSuspensionView::status).containsExactly("ACTIVE");
        assertThat(asOperator(() -> reviewStatus.forDebtor(creditorA, debtor)).breakSuspension().suspensions())
                .extracting(BreakSuspensionView::agreementId).containsExactly(agreement.id());
        assertThat(asFinance1(() -> payments.reviewQueue(PageQuery.of(0, 20))).content())
                .filteredOn(v -> v.id().equals(pending.id())).singleElement()
                .satisfies(v -> assertThat(v.breakSuspension().suspendedUntil()).isEqualTo(breakDay.plusDays(MAX_DAYS)));

        // dias seguintes dentro do prazo: continua suspensa, sem nova suspensão nem novos eventos
        daily(breakDay.plusDays(1));
        daily(breakDay.plusDays(MAX_DAYS));
        assertThat(status()).isEqualTo("FORMALIZED");
        assertThat(suspension().id()).isEqualTo(active.id());
        assertThat(count("select count(*) from negotiation.agreement_break_suspension where agreement_id = ?",
                agreement.id())).isEqualTo(1);

        // aprovação (outra pessoa): encerra a suspensão na mesma transação e a parcela baixa normalmente
        asFinance1(() -> payments.approve(pending.id(), null));
        BreakSuspensionView ended = suspension();
        assertThat(ended.status()).isEqualTo("ENDED");
        assertThat(ended.endReason()).isEqualTo("APPROVED");
        assertThat(ended.graceUntil()).isNull();
        awaitTrue("evento de negócio do encerramento", () -> businessEvents("agreement.break_suspension_ended") == 1);
        assertThat(audits("agreement.break_suspension_ended")).isEqualTo(1);
        awaitConsumed("negotiation.payment-confirmed", 1);
        settle();
        awaitTrue("1ª parcela baixada", () -> "PAID".equals(agreement(agreement.id()).installments().getFirst().status()));

        // com a 1ª paga só uma parcela atrasa além da tolerância: não quebra; reconciliar de novo não muda nada
        daily(breakDay.plusDays(MAX_DAYS + 1));
        assertThat(status()).isEqualTo("IN_PAYMENT");
        asFinance1(() -> {
            breakSuspensions.reconcile(agreement.id());
            return null;
        });
        assertThat(businessEvents("agreement.break_suspension_ended")).isEqualTo(1);
    }

    @Test
    void rejectionRestoresTheBreakAfterTheGracePeriodAndTheInstallmentDoesNotSuspendAgain() {
        scenario("recusa");
        PaymentView pending = inform(first(), "DEP-2");
        daily(breakDay);
        assertThat(suspension().status()).isEqualTo("ACTIVE");

        asFinance1(() -> payments.reject(pending.id(), "Comprovante de outra pessoa"));
        BreakSuspensionView ended = suspension();
        assertThat(ended.endReason()).isEqualTo("REJECTED");
        // carência conta do dia da recusa, nunca antes do início da suspensão
        assertThat(ended.graceUntil()).isEqualTo(breakDay.plusDays(GRACE_DAYS));
        assertThat(asOperator(() -> reviewStatus.forAgreement(agreement.id())).breakSuspension().suspensions())
                .singleElement().satisfies(s -> assertThat(s.graceUntil()).isEqualTo(breakDay.plusDays(GRACE_DAYS)));

        // durante a carência o devedor pode pagar de novo; um novo externo informado não reinicia a suspensão
        inform(first(), "DEP-3");
        daily(breakDay.plusDays(GRACE_DAYS));
        assertThat(status()).isEqualTo("FORMALIZED");

        // depois da carência a quebra volta a valer, mesmo com o novo pagamento em revisão (parcela já usada)
        daily(breakDay.plusDays(GRACE_DAYS + 1));
        assertThat(status()).isEqualTo("BROKEN");
        assertThat(count("select count(*) from negotiation.agreement_break_suspension where agreement_id = ?",
                agreement.id())).isEqualTo(1);
        assertThat(meterRegistry.find("negotiation.agreement.break_evaluations").tag("outcome", "BREAK_SUSPENSION_USED")
                .counter()).isNotNull();
    }

    @Test
    void maximumSuspensionReleasesTheBreakAndHighlightsThePendingReview() {
        scenario("prazo");
        PaymentView pending = inform(first(), "DEP-4");
        daily(breakDay);
        daily(breakDay.plusDays(MAX_DAYS));
        assertThat(status()).isEqualTo("FORMALIZED");

        // dia seguinte ao prazo: a suspensão termina pelo prazo e o acordo quebra
        daily(breakDay.plusDays(MAX_DAYS + 1));
        BreakSuspensionView ended = suspension();
        assertThat(ended.endReason()).isEqualTo("DEADLINE");
        assertThat(status()).isEqualTo("BROKEN");
        awaitTrue("evento do encerramento pelo prazo", () -> count("select count(*) from shared.business_event "
                + "where event_type = 'agreement.break_suspension_ended' and reference_id = ? "
                + "and attributes ->> 'reason' = 'DEADLINE'", agreement.id()) == 1);

        // quem revisa vê o pagamento em destaque: na fila (prazo vencido) e em "Minhas pendências" (vencido)
        assertThat(asFinance1(() -> payments.reviewQueue(PageQuery.of(0, 20))).content())
                .filteredOn(v -> v.id().equals(pending.id())).singleElement()
                .satisfies(v -> assertThat(v.breakSuspension().endReason()).isEqualTo("DEADLINE"));
        assertThat(asFinance1(() -> pendingWork.mine()).items()).filteredOn(i -> i.type().equals("payment.external_review"))
                .singleElement().satisfies(i -> {
                    assertThat(i.count()).isEqualTo(1);
                    assertThat(i.overdue()).isEqualTo(1);
                    assertThat(i.severity()).isEqualTo("OVERDUE");
                });

        // a revisão ainda pode acontecer: o acordo quebrado não recebe a baixa (não aplicado, como antes)
        asFinance1(() -> payments.approve(pending.id(), null));
        assertThat(suspension().endReason()).isEqualTo("DEADLINE");
    }

    @Test
    void suspensionIsIsolatedByTenantAndCreditor() {
        scenario("isolamento-a");
        inform(first(), "DEP-5");
        daily(breakDay);
        UUID agreementA = agreement.id();
        UUID tenantA = tenant;
        assertThat(suspension().status()).isEqualTo("ACTIVE");

        // escopo por credor: quem só vê o credor B não vê a suspensão nem o resumo do acordo do credor A
        assertThat(asOperatorB(() -> breakSuspensions.latest(List.of(agreementA)))).isEmpty();
        assertThatThrownBy(() -> asOperatorB(() -> reviewStatus.forAgreement(agreementA)))
                .isInstanceOf(ResourceNotFoundException.class);

        // outra assessoria: não enxerga a suspensão e o job dela não mexe na da assessoria A
        LocalDate dayA = breakDay;
        scenario("isolamento-b");
        assertThat(asOperator(() -> breakSuspensions.latest(List.of(agreementA)))).isEmpty();
        daily(dayA.plusDays(MAX_DAYS + 1));
        assertThat(count("select count(*) from negotiation.agreement_break_suspension")).isZero();
        tenant = tenantA;
        assertThat(count("select count(*) from negotiation.agreement_break_suspension where agreement_id = ? "
                + "and status = 'ACTIVE'", agreementA)).isEqualTo(1);
    }
}
