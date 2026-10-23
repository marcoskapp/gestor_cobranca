package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionView;
import br.com.gestorcobranca.payment.application.port.in.PaymentSandboxUseCases.SimulatedNotification;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.DivergenceFilter;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.DivergenceView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.ResolveCommand;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementImportView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementUpload;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conciliação (F15) contra PostgreSQL real: arquivo de retorno confirma instrução sem webhook (e o
 * webhook posterior não duplica a baixa), conciliação por identificador da transação, divergências
 * (valor, sem correspondência), linhas repetidas em outro arquivo sem nova baixa, mesmo arquivo recusado e
 * resolução auditada com segregação (quem informou não concilia o próprio pagamento).
 */
class PaymentReconciliationIT extends PaymentTestSupport {

    private StatementImportView importCsv(String name, String csv) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        StatementImportView view = asFinance2(() -> reconciliation.importStatement(new StatementUpload("CSV_GENERIC", null,
                name, "text/csv", bytes.length, new ByteArrayInputStream(bytes))));
        assertThat(view.status()).isEqualTo("RECEIVED");
        settle();
        return asFinance2(() -> reconciliation.getImport(view.id()));
    }

    private PaymentView external(String amount, String reference, UUID title) {
        return asFinance1(() -> payments.registerExternal(new ExternalPaymentCommand(creditorB, debtor,
                new BigDecimal(amount), Instant.now().minus(2, ChronoUnit.HOURS), "TRANSFER", "AGENCY_ACCOUNT", reference,
                null, new TargetInput("TITLES", null, List.of(title), null)), pdf(), null));
    }

    @Test
    void returnFileWebhookStatementDivergencesAndAuditedResolution() {
        provision("reconcile");
        AgreementView agreement = formalizeAgreement(2);
        InstructionView pix = pixFor(agreement.id(), agreement.installments().getFirst().id(), "PIX_IMMEDIATE");
        PaymentView doc1 = external("100.00", "DOC-1", titleB);
        String amount = pix.amount().toPlainString().replace('.', ',');

        String day = today.toString();
        String first = """
                data;valor;tipo;id_transacao;referencia;descricao
                %s;%s;C;;%s;PIX RECEBIDO MARIA DEVEDORA
                %s;100,00;C;DOC-1;;TED RECEBIDA
                %s;77,00;C;;;CREDITO NAO IDENTIFICADO CPF 529.982.247-25
                %s;10,00;D;;;TARIFA
                %s;99,00;C;;%s;VALOR DIVERGENTE
                """.formatted(day, amount, pix.txid(), day, day, day, day, doc1.id());
        StatementImportView imported = importCsv("extrato-1.csv", first);
        assertThat(imported.status()).isEqualTo("PROCESSED");
        assertThat(imported.totalLines()).isEqualTo(5);
        assertThat(imported.createdPayments()).isEqualTo(1);
        assertThat(imported.reconciledLines()).isEqualTo(2);
        assertThat(imported.divergentLines()).isEqualTo(2);
        assertThat(imported.ignoredLines()).isEqualTo(1);

        // arquivo de retorno confirmou a instrução sem webhook: pagamento integrado, conciliado e alocado
        assertThat(asOperator(() -> instructions.get(pix.id())).status()).isEqualTo("PAID");
        PaymentView fromStatement = asFinance1(() -> payments.get(asOperator(() -> instructions.get(pix.id())).paymentId()));
        assertThat(fromStatement.status()).isEqualTo("RECONCILED");
        assertThat(fromStatement.reviewMode()).isEqualTo("STATEMENT");
        assertThat(fromStatement.reconciliationMode()).isEqualTo("STATEMENT");
        awaitConsumed("negotiation.payment-confirmed", 1);
        settle();
        awaitTrue("parcela paga", () -> "PAID".equals(agreement(agreement.id()).installments().getFirst().status()));
        assertThat(asFinance1(() -> payments.get(doc1.id())).status()).isEqualTo("RECONCILED");

        // o webhook do mesmo Pix chega depois: vincula o endToEndId, sem nova baixa
        SimulatedNotification late = simulatePay(pix.id(), null);
        assertThat(postWebhook(late.routingKey(), late.body(), late.signature())).isEqualTo(200);
        settle();
        assertThat(count("select count(*) from payment.payment where instruction_id = ?", pix.id())).isEqualTo(1);
        assertThat(asFinance1(() -> payments.get(fromStatement.id())).providerTransactionId()).isEqualTo(late.endToEndId());

        // mesmo arquivo de novo → recusado
        byte[] same = first.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> asFinance2(() -> reconciliation.importStatement(new StatementUpload("CSV_GENERIC", null,
                "extrato-1.csv", "text/csv", same.length, new ByteArrayInputStream(same)))))
                .isInstanceOf(ConflictException.class);
        // CNAB ainda sem leitor
        assertThatThrownBy(() -> asFinance2(() -> reconciliation.importStatement(new StatementUpload("CNAB_240", null,
                "retorno.ret", "text/plain", same.length, new ByteArrayInputStream(same)))))
                .isInstanceOf(BusinessRuleException.class);

        // extrato sobreposto: as mesmas transações não geram nova baixa; um crédito novo sem correspondência
        PaymentView doc9 = external("55.00", "DOC-9", titleB);
        String second = """
                data;valor;tipo;id_transacao;referencia;descricao
                %s;%s;C;;%s;PIX RECEBIDO MARIA DEVEDORA
                %s;100,00;C;DOC-1;;TED RECEBIDA
                %s;55,00;C;;;DEPOSITO SEM REFERENCIA
                """.formatted(day, amount, pix.txid(), day, day);
        StatementImportView overlap = importCsv("extrato-2.csv", second);
        assertThat(overlap.ignoredLines()).isEqualTo(2);
        assertThat(overlap.divergentLines()).isEqualTo(1);
        assertThat(count("select count(*) from payment.payment")).isEqualTo(3);

        List<DivergenceView> open = asFinance2(() -> reconciliation.divergences(new DivergenceFilter("OPEN", null),
                PageQuery.of(0, 20))).content();
        assertThat(open).extracting(DivergenceView::type)
                .containsExactlyInAnyOrder("UNMATCHED", "AMOUNT_MISMATCH", "UNMATCHED");
        DivergenceView unidentified = open.stream().filter(d -> d.type().equals("UNMATCHED")
                && d.amount().compareTo(new BigDecimal("77.00")) == 0).findFirst().orElseThrow();
        assertThat(unidentified.description()).doesNotContain("529.982.247-25").contains("CREDITO NAO IDENTIFICADO");
        assertThat(count("select count(*) from payment.statement_line where description_encrypted like '%CREDITO%'"))
                .isZero();

        // resolução: o depósito de 55 é o pagamento informado por F1 — F1 não concilia o próprio pagamento
        DivergenceView deposit = open.stream().filter(d -> d.amount().compareTo(new BigDecimal("55.00")) == 0).findFirst()
                .orElseThrow();
        assertThatThrownBy(() -> asFinance1(() -> reconciliation.resolve(deposit.id(), new ResolveCommand("MATCHED_PAYMENT",
                doc9.id(), "Depósito do devedor")))).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não pode conciliá-lo");
        DivergenceView matched = asFinance2(() -> reconciliation.resolve(deposit.id(), new ResolveCommand("MATCHED_PAYMENT",
                doc9.id(), "Depósito do devedor conferido")));
        assertThat(matched.status()).isEqualTo("RESOLVED");
        assertThat(asFinance1(() -> payments.get(doc9.id())).reconciliationMode()).isEqualTo("MANUAL");
        assertThatThrownBy(() -> asFinance2(() -> reconciliation.resolve(unidentified.id(), new ResolveCommand("DISMISSED",
                null, null)))).isInstanceOf(BusinessRuleException.class);
        assertThat(asFinance2(() -> reconciliation.resolve(unidentified.id(), new ResolveCommand("DISMISSED", null,
                "Crédito de outra operação"))).status()).isEqualTo("DISMISSED");

        assertThat(count("select count(*) from shared.audit_event where action = 'payment_divergence.resolved'")).isEqualTo(2);
        assertThat(count("select count(*) from shared.business_event where event_type = 'payment.divergence'")).isEqualTo(5);
        assertThat(count("select count(*) from shared.business_event where event_type = 'payment.reconciled' "
                + "and attributes ->> 'mode' = 'STATEMENT'")).isEqualTo(2);
    }
}
