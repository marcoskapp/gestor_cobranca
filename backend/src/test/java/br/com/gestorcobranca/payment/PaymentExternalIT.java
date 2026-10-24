package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.AllocationView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceContent;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceUpload;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReallocateCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReverseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pagamentos externos contra PostgreSQL real: evidência obrigatória (tipo conferido pelo conteúdo),
 * confirmação automática até o limite e segregação acima dele (quem informa não aprova), idempotência,
 * títulos avulsos lançados no razão, sobra e devolução, estorno manual e realocação do valor não
 * aplicado pelo negotiation (acordo encerrado).
 */
class PaymentExternalIT extends PaymentTestSupport {

    @Autowired
    PaymentSettingsUseCases settings;

    private final Instant paidAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    private ExternalPaymentCommand toTitleB(String amount, String reference) {
        return new ExternalPaymentCommand(creditorB, debtor, new BigDecimal(amount), paidAt,
                "TRANSFER", "AGENCY_ACCOUNT", reference, "Depósito identificado", new TargetInput("TITLES", null,
                List.of(titleB), null));
    }

    @Test
    void evidenceDualControlTitlesLedgerLeftoverRefundAndManualReversal() throws Exception {
        provision("external");

        // evidência obrigatória e conferida pelo conteúdo
        assertThatThrownBy(() -> asOperator(() -> payments.registerExternal(toTitleB("100.00", null), null, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Evidência");
        byte[] fake = "não é um PDF".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> asOperator(() -> payments.registerExternal(toTitleB("100.00", null),
                new EvidenceUpload("x.pdf", "application/pdf", fake.length, new ByteArrayInputStream(fake)), null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("não corresponde");

        // até o limite (R$ 500,00): confirmado no registro, lançado no razão do título avulso
        PaymentView small = asOperator(() -> payments.registerExternal(toTitleB("100.00", "DOC-1"), pdf(), "idem-1"));
        assertThat(small.status()).isEqualTo("CONFIRMED");
        assertThat(small.reviewMode()).isEqualTo("AUTO_LOW_RISK");
        assertThat(small.evidenceSha256()).hasSize(64);
        assertThat(small.allocations()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("TITLES");
            assertThat(a.outcome()).isEqualTo("NOT_APPLICABLE");
        });
        assertThat(system(() -> debtTitles.get(titleB)).paidTotal()).isEqualByComparingTo("100.00");
        awaitConsumed("collection.payment-confirmed", 1);
        // mesma chave de idempotência e mesmo conteúdo → mesmo pagamento, sem nova evidência nem baixa
        PaymentView repeated = asOperator(() -> payments.registerExternal(toTitleB("100.00", "DOC-1"), pdf(), "idem-1"));
        assertThat(repeated.id()).isEqualTo(small.id());
        assertThat(count("select count(*) from payment.payment")).isEqualTo(1);

        // acima do limite: aguarda segunda pessoa com PAYMENT_REVIEW; quem informou não revisa
        PaymentView big = asFinance1(() -> payments.registerExternal(toTitleB("900.00", "DOC-2"), pdf(), null));
        assertThat(big.status()).isEqualTo("INFORMED");
        assertThat(big.reviewThresholdExceeded()).isTrue();
        assertThat(system(() -> debtTitles.get(titleB)).paidTotal()).isEqualByComparingTo("100.00");
        assertThat(asFinance1(() -> payments.reviewQueue(PageQuery.of(0, 10))).content()).isEmpty();
        assertThat(asFinance2(() -> payments.reviewQueue(PageQuery.of(0, 10))).content()).extracting(PaymentView::id)
                .containsExactly(big.id());
        // quem aprova vê o destino solicitado antes de existir alocação (na fila e no detalhe)
        assertThat(big.allocations()).isEmpty();
        assertThat(asFinance2(() -> payments.reviewQueue(PageQuery.of(0, 10))).content().getFirst().requestedTarget())
                .satisfies(t -> {
                    assertThat(t.kind()).isEqualTo("TITLES");
                    assertThat(t.agreementId()).isNull();
                    assertThat(t.itemIds()).containsExactly(titleB);
                    assertThat(t.strategy()).isEqualTo("OLDEST_FIRST");
                });
        assertThat(asFinance2(() -> payments.get(big.id())).requestedTarget().itemIds()).containsExactly(titleB);
        assertThatThrownBy(() -> asFinance1(() -> payments.approve(big.id(), null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("não pode revisá-lo");
        assertThatThrownBy(() -> asOperator(() -> payments.approve(big.id(), null)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        PaymentView approved = asFinance2(() -> payments.approve(big.id(), "Comprovante conferido no extrato"));
        assertThat(approved.status()).isEqualTo("CONFIRMED");
        assertThat(approved.reviewedBy()).isEqualTo(finance2);
        assertThat(system(() -> debtTitles.get(titleB)).status()).isEqualTo("SETTLED");

        // recusa exige motivo e não produz baixa
        PaymentView doubtful = asOperator(() -> payments.registerExternal(toTitleB("600.00", "DOC-3"), pdf(), null));
        assertThatThrownBy(() -> asFinance1(() -> payments.reject(doubtful.id(), null))).isInstanceOf(DomainException.class);
        assertThat(asFinance1(() -> payments.reject(doubtful.id(), "Comprovante ilegível")).status()).isEqualTo("REJECTED");

        // título já quitado: o valor fica como sobra (não alocado) e entra na fila de realocação
        PaymentView extra = asOperator(() -> payments.registerExternal(toTitleB("50.00", "DOC-4"), pdf(), null));
        assertThat(extra.status()).isEqualTo("CONFIRMED");
        assertThat(extra.allocations()).isEmpty();
        assertThat(extra.unallocatedAmount()).isEqualByComparingTo("50.00");
        assertThat(asFinance1(() -> payments.pendingReallocation(PageQuery.of(0, 10))).content()).extracting(PaymentView::id)
                .containsExactly(extra.id());
        // devolução ao pagador registrada (com referência) zera a sobra
        PaymentView refunded = asFinance1(() -> payments.reallocate(extra.id(), new ReallocateCommand(null,
                new BigDecimal("50.00"), null, "TED-DEVOLUCAO-1", "Valor pago a maior")));
        assertThat(refunded.unallocatedAmount()).isEqualByComparingTo("0");
        assertThat(refunded.allocations()).singleElement().satisfies(a -> assertThat(a.kind()).isEqualTo("REFUND"));
        assertThat(asFinance1(() -> payments.pendingReallocation(PageQuery.of(0, 10))).content()).isEmpty();

        // evidência: download registrado no log de acesso
        EvidenceContent evidence = asFinance1(() -> payments.openEvidence(small.id()));
        try (InputStream in = evidence.content()) {
            assertThat(in.readAllBytes()).isEqualTo(PDF);
        }
        assertThat(count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED'")).isEqualTo(1);

        // estorno manual (PAYMENT_REVERSE): compensação no razão do título, que volta a ter saldo
        assertThatThrownBy(() -> asOperator(() -> payments.reverse(approved.id(), new ReverseCommand("CHARGEBACK", "x"))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        PaymentView reversed = asFinance1(() -> payments.reverse(approved.id(), new ReverseCommand("BANK_RETURN",
                "Transferência devolvida pelo banco")));
        assertThat(reversed.status()).isEqualTo("REVERSED");
        assertThat(system(() -> debtTitles.get(titleB)).status()).isEqualTo("OPEN");
        assertThat(system(() -> debtTitles.get(titleB)).paidTotal()).isEqualByComparingTo("100.00");
        assertThat(count("select count(*) from debt.title_ledger_entry where entry_type = 'REVERSAL' and reference_id = ?",
                reversed.allocations().getFirst().id())).isPositive();
        assertThatThrownBy(() -> asFinance1(() -> payments.reverse(approved.id(), new ReverseCommand("OTHER", "de novo"))))
                .isInstanceOf(DomainException.class);
        awaitConsumed("collection.payment-reversed", 1);
        // estorno é append-only no banco
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update payment.payment_reversal set reason = 'OTHER'")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        for (String type : List.of("payment.informed", "payment.confirmed", "payment.rejected", "payment.reversed",
                "payment.reallocated")) {
            assertThat(count("select count(*) from shared.business_event where event_type = ?", type)).as(type).isPositive();
        }
        String attributes = system(() -> jdbcTemplate.queryForObject("select string_agg(attributes::text, ' ') "
                + "from shared.business_event where event_type like 'payment%'", String.class));
        assertThat(attributes).doesNotContain("Depósito identificado", "Maria", "529");
    }

    @Test
    void unappliedAgreementPaymentIsReallocatedToTitlesWithCompensation() {
        provision("unapplied");
        asAdmin(() -> settings.update(new SettingsCommand(new BigDecimal("100000.00"), 86_400, 30, 3)));
        AgreementView agreement = formalizeAgreement(1);
        BigDecimal total = agreement.installments().getFirst().amount();

        // pagamento confirmado para o acordo e cancelamento do acordo na mesma transação: o negotiation recebe o
        // pagamento já com o acordo encerrado e devolve "não aplicado"
        PaymentView payment = asAdmin(() -> {
            PaymentView p = payments.registerExternal(new ExternalPaymentCommand(creditorA, debtor, total,
                    Instant.now().minus(1, ChronoUnit.HOURS), "PIX", "AGENCY_ACCOUNT", "E2E-EXTERNO", null,
                    new TargetInput("AGREEMENT", agreement.id(), List.of(), null)), pdf(), null);
            agreements.cancel(agreement.id(), "Devedor desistiu");
            return p;
        });
        assertThat(payment.status()).isEqualTo("CONFIRMED");
        assertThat(payment.allocations()).singleElement().satisfies(a -> assertThat(a.outcome()).isEqualTo("PENDING"));
        // destino solicitado: acordo sem parcelas escolhidas (= todas em aberto), sem estratégia de títulos
        assertThat(payment.requestedTarget()).satisfies(t -> {
            assertThat(t.kind()).isEqualTo("AGREEMENT");
            assertThat(t.agreementId()).isEqualTo(agreement.id());
            assertThat(t.itemIds()).isEmpty();
            assertThat(t.strategy()).isNull();
        });
        awaitConsumed("payment.agreement-payment-recorded", 1);
        AllocationView unapplied = asFinance1(() -> payments.get(payment.id())).allocations().getFirst();
        assertThat(unapplied.outcome()).isEqualTo("UNAPPLIED");
        assertThat(unapplied.availableForReallocation()).isEqualByComparingTo(total);
        assertThat(asFinance1(() -> payments.pendingReallocation(PageQuery.of(0, 10))).content()).extracting(PaymentView::id)
                .containsExactly(payment.id());
        assertThat(asManager(() -> agreements.payments(agreement.id()))).singleElement()
                .satisfies(p -> assertThat(p.status()).isEqualTo("UNAPPLIED"));

        // realocação do não aplicado para os títulos (liberados pelo cancelamento): razão do título + compensação
        UUID allocationId = unapplied.id();
        assertThatThrownBy(() -> asOperator(() -> payments.reallocate(payment.id(), new ReallocateCommand(allocationId,
                total, new TargetInput("TITLES", null, List.of(title60, title30), null), null, "Realocação"))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        PaymentView reallocated = asFinance1(() -> payments.reallocate(payment.id(), new ReallocateCommand(allocationId,
                total, new TargetInput("TITLES", null, List.of(title60, title30), "OLDEST_FIRST"), null,
                "Acordo cancelado antes da baixa")));
        // os títulos recebem até o saldo do razão (principal); o que exceder continua na fila de realocação
        BigDecimal expected = total.min(new BigDecimal("1500.00"));
        assertThat(reallocated.pendingReallocation()).isEqualByComparingTo(total.subtract(expected));
        assertThat(reallocated.allocations()).hasSize(2);
        AllocationView titles = reallocated.allocations().get(1);
        assertThat(titles.origin()).isEqualTo("REALLOCATION");
        assertThat(titles.sourceAllocationId()).isEqualTo(allocationId);
        assertThat(titles.lines().getFirst().titleId()).isEqualTo(title60);
        BigDecimal paid = system(() -> debtTitles.get(title60)).paidTotal().add(system(() -> debtTitles.get(title30))
                .paidTotal());
        assertThat(paid).isEqualByComparingTo(expected);
        assertThat(titles.amount()).isEqualByComparingTo(expected);
        // compensação publicada para a alocação liberada (o negotiation ignora; o incentive retira a base)
        awaitConsumed("negotiation.payment-reversed", 1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'payment.reallocated'")).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'payment.unapplied'")).isEqualTo(1);
    }
}
