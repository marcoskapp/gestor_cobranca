package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.CreateInstructionCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionView;
import br.com.gestorcobranca.payment.application.port.in.PaymentSandboxUseCases.SimulatedNotification;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentFilter;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.DivergenceFilter;
import br.com.gestorcobranca.payment.domain.service.WebhookAuthentication;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxo integrado contra PostgreSQL real: instrução Pix → registro no PSP (simulado) → webhook
 * (HMAC, recibo, replay, janela) → confirmação por consulta ao PSP → parcela paga no negotiation →
 * acordo quitado; Pix com vencimento; devolução total (estorno) e parcial (divergência).
 */
class PaymentPixFlowIT extends PaymentTestSupport {

    private SimulatedNotification payByWebhook(InstructionView instruction, BigDecimal amount) {
        SimulatedNotification n = simulatePay(instruction.id(), amount);
        assertThat(postWebhook(n.routingKey(), n.body(), n.signature())).isEqualTo(200);
        settle();
        return n;
    }

    @Test
    void instructionWebhookConfirmationInstallmentPaidAndAgreementSettled() {
        provision("pix");
        AgreementView agreement = formalizeAgreement(2);
        InstallmentView first = agreement.installments().getFirst();
        InstallmentView second = agreement.installments().get(1);

        // instrução Pix imediato: PENDING → registrada no PSP (job) → ACTIVE com copia e cola
        InstructionView pix = pixFor(agreement.id(), first.id(), "PIX_IMMEDIATE");
        assertThat(pix.status()).isEqualTo("ACTIVE");
        assertThat(pix.amount()).isEqualByComparingTo(first.amount());
        assertThat(pix.txid()).hasSize(32);
        assertThat(pix.pixCopyPaste()).startsWith("000201");
        assertThat(pix.expiresAt()).isAfter(Instant.now().plus(23, ChronoUnit.HOURS));
        // mesma instrução aberta é reaproveitada, não duplicada
        InstructionView again = asOperator(() -> instructions.create(new CreateInstructionCommand("PIX_IMMEDIATE",
                agreement.id(), first.id(), null, null, null, null, "WHATSAPP"), null));
        assertThat(again.id()).isEqualTo(pix.id());
        // boleto e link: só portas (issue #12)
        assertThatThrownBy(() -> asOperator(() -> instructions.create(new CreateInstructionCommand("BOLETO",
                agreement.id(), first.id(), null, null, null, null, null), null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("issue #12");
        // valor acima do saldo da parcela é recusado
        assertThatThrownBy(() -> asOperator(() -> instructions.create(new CreateInstructionCommand("PIX_IMMEDIATE",
                agreement.id(), first.id(), null, first.amount().add(BigDecimal.ONE), null, null, null), null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("saldo");

        // pagamento pelo webhook (corpo da API Pix assinado com HMAC) → consulta ao PSP → confirmado e conciliado
        double ledgerBefore = ledgerPostingsByPayment();
        SimulatedNotification n = payByWebhook(pix, null);
        awaitConsumed("negotiation.payment-confirmed", 1);
        awaitConsumed("payment.agreement-payment-recorded", 1);
        settle();
        PaymentView payment = asFinance1(() -> payments.search(new PaymentFilter(null, debtor, null, null, null, null, null,
                null), PageQuery.of(0, 10))).content().getFirst();
        assertThat(payment.source()).isEqualTo("PROVIDER");
        assertThat(payment.status()).isEqualTo("RECONCILED");
        assertThat(payment.reconciliationMode()).isEqualTo("PROVIDER_API");
        assertThat(payment.providerTransactionId()).isEqualTo(n.endToEndId());
        assertThat(payment.allocations()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("AGREEMENT");
            assertThat(a.agreementId()).isEqualTo(agreement.id());
            assertThat(a.lines()).singleElement().satisfies(l -> assertThat(l.installmentId()).isEqualTo(first.id()));
        });
        awaitTrue("resultado da baixa registrado", () -> "APPLIED".equals(asFinance1(() -> payments.get(payment.id()))
                .allocations().getFirst().outcome()));
        assertThat(asOperator(() -> instructions.get(pix.id())).status()).isEqualTo("PAID");
        AgreementView inPayment = agreement(agreement.id());
        assertThat(inPayment.status()).isEqualTo("IN_PAYMENT");
        assertThat(inPayment.installments().getFirst().status()).isEqualTo("PAID");
        // o paymentId publicado é o id da alocação (unidade de baixa)
        assertThat(asManager(() -> agreements.payments(agreement.id()))).singleElement()
                .satisfies(p -> assertThat(p.paymentId()).isEqualTo(payment.allocations().getFirst().id()));
        // o payment não lança no razão do título as alocações de acordo: quem lança é o negotiation
        assertThat(ledgerPostingsByPayment() - ledgerBefore).isZero();
        assertThat(count("select count(*) from debt.title_ledger_entry where reference_id = ? and entry_type = 'PAYMENT'",
                payment.allocations().getFirst().id())).isPositive();
        awaitTrue("comissão: fato de pagamento no incentive", () -> count(
                "select count(*) from incentive.payment_fact where payment_id = ?", payment.allocations().getFirst().id()) == 1);

        // replay: corpo idêntico → recibo, nenhum efeito; mesmo endToEndId com outro horário → sem nova baixa
        assertThat(postWebhook(n.routingKey(), n.body(), n.signature())).isEqualTo(200);
        String other = n.body().replaceFirst("\"horario\":\"[^\"]+\"", "\"horario\":\""
                + Instant.now().atOffset(ZoneOffset.UTC) + "\"");
        assertThat(postWebhook(n.routingKey(), other, sign(other))).isEqualTo(200);
        settle();
        assertThat(count("select count(*) from payment.payment")).isEqualTo(1);
        // fora da janela de replay → descartada (nenhuma consulta agendada)
        String stale = n.body().replaceFirst("\"horario\":\"[^\"]+\"", "\"horario\":\"2020-01-01T00:00:00Z\"")
                .replace(n.endToEndId(), "E" + "1".repeat(31));
        assertThat(postWebhook(n.routingKey(), stale, sign(stale))).isEqualTo(200);
        assertThat(count("select count(*) from shared.processing_job where type = 'payment.pix_sync' "
                + "and payload ->> 'endToEndId' = ?", "E" + "1".repeat(31))).isZero();
        // assinatura inválida, ausente ou rota desconhecida → recusa antes de qualquer processamento
        assertThat(postWebhook(n.routingKey(), n.body(), WebhookAuthentication.hmacHeader("outro-segredo-qualquer-com-32-chars",
                n.body().getBytes(StandardCharsets.UTF_8)))).isEqualTo(401);
        assertThat(postWebhook(n.routingKey(), n.body(), null)).isEqualTo(401);
        assertThat(postWebhook("rota-inexistente-" + "x".repeat(20), n.body(), n.signature())).isEqualTo(404);
        assertThat(postWebhook(n.routingKey(), "{\"pix\":", sign("{\"pix\":"))).isEqualTo(400);
        assertThat(count("select count(*) from shared.access_log where event_type = 'WEBHOOK_REJECTED'")).isGreaterThanOrEqualTo(2);

        // 2ª parcela por Pix com vencimento (cobv exige o pagador: leitura do documento registrada no log de acesso)
        InstructionView due = pixFor(agreement.id(), second.id(), "PIX_DUE");
        assertThat(due.status()).isEqualTo("ACTIVE");
        assertThat(due.dueDate()).isNotNull();
        assertThat(count("select count(*) from shared.access_log where event_type = 'SENSITIVE_DATA_VIEWED'")).isEqualTo(1);
        payByWebhook(due, null);
        awaitConsumed("negotiation.payment-confirmed", 2);
        settle();
        awaitTrue("acordo quitado", () -> "SETTLED".equals(agreement(agreement.id()).status()));
        assertThat(agreement(agreement.id()).installments()).extracting(InstallmentView::status)
                .containsExactly("PAID", "PAID");
        awaitTrue("títulos quitados no razão", () -> "SETTLED".equals(system(() -> debtTitles.get(title60)).status()));

        // eventos de negócio e auditoria sem dados pessoais
        for (String type : List.of("payment_instruction.created", "payment.confirmed", "payment.reconciled")) {
            assertThat(count("select count(*) from shared.business_event where event_type = ?", type)).as(type).isPositive();
        }
        String attributes = system(() -> jdbcTemplate.queryForObject("select string_agg(attributes::text, ' ') "
                + "from shared.business_event where event_type like 'payment%'", String.class));
        assertThat(attributes).doesNotContain("Maria", "529", "982");
        assertThat(count("select count(*) from shared.audit_event where action in ('payment_instruction.created', "
                + "'payment.confirmed', 'payment.allocated', 'payment.reconciled')")).isGreaterThanOrEqualTo(8);
    }

    @Test
    void providerRefundReversesThePaymentAndPartialRefundOpensDivergence() {
        provision("refund");
        AgreementView agreement = formalizeAgreement(2);
        InstallmentView first = agreement.installments().getFirst();
        SimulatedNotification paid = payByWebhook(pixFor(agreement.id(), first.id(), "PIX_IMMEDIATE"), null);
        awaitConsumed("negotiation.payment-confirmed", 1);
        settle();
        assertThat(agreement(agreement.id()).installments().getFirst().status()).isEqualTo("PAID");

        // devolução total pelo PSP → estorno compensatório: parcela volta a aberta, nada é apagado
        SimulatedNotification refund = simulateRefund(paid.endToEndId(), null);
        assertThat(postWebhook(refund.routingKey(), refund.body(), refund.signature())).isEqualTo(200);
        settle();
        awaitConsumed("negotiation.payment-reversed", 1);
        settle();
        PaymentView reversed = asFinance1(() -> payments.search(new PaymentFilter(null, debtor, "REVERSED", null, null, null,
                null, null), PageQuery.of(0, 10))).content().getFirst();
        assertThat(reversed.reversal().reason()).isEqualTo("PROVIDER_REFUND");
        assertThat(reversed.reversal().source()).isEqualTo("PROVIDER");
        assertThat(reversed.allocations()).allSatisfy(a -> assertThat(a.status()).isEqualTo("REVERSED"));
        awaitTrue("parcela reaberta", () -> !"PAID".equals(agreement(agreement.id()).installments().getFirst().status()));
        // a mesma devolução notificada de novo não estorna outra vez
        String refundAgain = refund.body() + " ";
        assertThat(postWebhook(refund.routingKey(), refundAgain, sign(refundAgain))).isEqualTo(200);
        settle();
        assertThat(count("select count(*) from payment.payment_reversal")).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'payment.reversed'")).isEqualTo(1);

        // novo pagamento da parcela e devolução parcial → divergência para análise (estorno é sempre total)
        InstructionView retry = pixFor(agreement.id(), first.id(), "PIX_IMMEDIATE");
        SimulatedNotification paidAgain = payByWebhook(retry, null);
        SimulatedNotification partial = simulateRefund(paidAgain.endToEndId(), new BigDecimal("10.00"));
        assertThat(postWebhook(partial.routingKey(), partial.body(), partial.signature())).isEqualTo(200);
        settle();
        assertThat(asFinance1(() -> reconciliation.divergences(new DivergenceFilter("OPEN", "PARTIAL_REFUND"),
                PageQuery.of(0, 10))).content()).singleElement()
                .satisfies(d -> assertThat(d.amount()).isEqualByComparingTo("10.00"));
        assertThat(count("select count(*) from payment.payment where status = 'RECONCILED'")).isEqualTo(1);
    }

    @Test
    void secondPixForTheSameInstructionIsKeptAllocatedAndFlaggedAsDuplicate() throws Exception {
        provision("duplicate");
        AgreementView agreement = formalizeAgreement(3);
        InstallmentView first = agreement.installments().getFirst();
        InstructionView pix = pixFor(agreement.id(), first.id(), "PIX_IMMEDIATE");
        // o devedor paga o mesmo QR Code duas vezes; as notificações chegam juntas e são processadas em paralelo
        SimulatedNotification one = simulatePay(pix.id(), null);
        SimulatedNotification two = simulatePay(pix.id(), null);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> postWebhook(one.routingKey(), one.body(), one.signature()));
            var b = pool.submit(() -> postWebhook(two.routingKey(), two.body(), two.signature()));
            assertThat(a.get()).isEqualTo(200);
            assertThat(b.get()).isEqualTo(200);
        } finally {
            pool.shutdown();
        }
        settle();
        awaitConsumed("negotiation.payment-confirmed", 2);
        settle();
        assertThat(count("select count(*) from payment.payment where instruction_id = ?", pix.id())).isEqualTo(2);
        assertThat(asFinance1(() -> reconciliation.divergences(new DivergenceFilter("OPEN", "DUPLICATE_PAYMENT"),
                PageQuery.of(0, 10))).content()).hasSize(1);
        // o segundo pagamento não fica na parcela já paga: vai para a seguinte (entrada ≠ demais parcelas → parcial)
        awaitTrue("segunda parcela parcialmente paga", () -> {
            List<InstallmentView> list = agreement(agreement.id()).installments();
            return "PAID".equals(list.get(0).status()) && "PARTIALLY_PAID".equals(list.get(1).status())
                    && list.get(1).paidAmount().compareTo(first.amount()) == 0;
        });
        assertThat(count("select count(*) from payment.payment_allocation_line where installment_id = ?", first.id()))
                .isEqualTo(1);
    }

    private static String sign(String body) {
        return WebhookAuthentication.hmacHeader(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8));
    }
}
