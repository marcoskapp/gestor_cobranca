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
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Conciliação (F15) contra PostgreSQL real: linha de extrato que cita uma instrução cria pagamento em revisão, que só
 * confirma a instrução com a aprovação de outra pessoa (SEG-20261124-C01; o webhook posterior não duplica a baixa),
 * conciliação por identificador da transação, divergências (valor, sem correspondência), linhas repetidas em outro
 * arquivo sem nova baixa, mesmo arquivo recusado e resolução auditada com segregação (quem informou não concilia o
 * próprio pagamento, nem manualmente nem com extrato que ele mesmo enviou).
 */
class PaymentReconciliationIT extends PaymentTestSupport {

    private StatementImportView importCsv(String name, String csv) {
        return importCsvAs(false, name, csv);
    }

    /** @param byFinance1 envia como F1 (por padrão, F2 envia os extratos) */
    private StatementImportView importCsvAs(boolean byFinance1, String name, String csv) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        java.util.function.Supplier<StatementImportView> upload = () -> reconciliation.importStatement(
                new StatementUpload("CSV_GENERIC", null, name, "text/csv", bytes.length, new ByteArrayInputStream(bytes)));
        StatementImportView view = byFinance1 ? asFinance1(upload::get) : asFinance2(upload::get);
        assertThat(view.status()).isEqualTo("RECEIVED");
        settle();
        return asFinance2(() -> reconciliation.getImport(view.id()));
    }

    private String lineStatus(UUID paymentId) {
        return system(() -> jdbcTemplate.queryForObject("select status from payment.statement_line where payment_id = ?", String.class, paymentId));
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
        assertThat(imported.reconciledLines()).isEqualTo(1);
        assertThat(imported.divergentLines()).isEqualTo(2);
        assertThat(imported.ignoredLines()).isEqualTo(1);

        // a linha cita a instrução: o extrato enviado por F2 não confirma sozinho — pagamento em revisão, instrução aberta
        assertThat(asOperator(() -> instructions.get(pix.id())).status()).isEqualTo("ACTIVE");
        UUID pendingId = system(() -> jdbcTemplate.queryForObject("select id from payment.payment where instruction_id = ?", UUID.class, pix.id()));
        PaymentView pending = asFinance1(() -> payments.get(pendingId));
        assertThat(pending.status()).isEqualTo("INFORMED");
        assertThat(pending.reviewMode()).isEqualTo("DUAL_CONTROL");
        assertThat(lineStatus(pendingId)).isEqualTo("PENDING_REVIEW");
        assertThat(count("select count(*) from payment.payment_allocation where payment_id = ?", pendingId)).isZero();
        // quem enviou o extrato não aprova; outra pessoa aprova → instrução paga, pagamento conciliado com a linha
        assertThatThrownBy(() -> asFinance2(() -> payments.approve(pendingId, null, false)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("não pode revisá-lo");
        assertThat(asFinance1(() -> payments.reviewAssessment(pendingId)).reason()).isEqualTo("STATEMENT_LINE");
        PaymentView fromStatement = asFinance1(() -> payments.approve(pendingId, "Conferido no extrato do banco", false));
        assertThat(fromStatement.status()).isEqualTo("RECONCILED");
        assertThat(fromStatement.reconciliationMode()).isEqualTo("STATEMENT");
        assertThat(asOperator(() -> instructions.get(pix.id())).status()).isEqualTo("PAID");
        assertThat(asOperator(() -> instructions.get(pix.id())).paymentId()).isEqualTo(pendingId);
        assertThat(lineStatus(pendingId)).isEqualTo("CREATED_PAYMENT");
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

        List<DivergenceView> open = asFinance2(() -> reconciliation.divergences(new DivergenceFilter("OPEN", null, null, null, null),
                PageQuery.of(0, 20))).content();
        assertThat(open).extracting(DivergenceView::type)
                .containsExactlyInAnyOrder("UNMATCHED", "AMOUNT_MISMATCH", "UNMATCHED");
        // filtros do drill-down do painel de confiabilidade: abertas no período (dia no fuso de operação) e credor
        assertThat(asFinance2(() -> reconciliation.divergences(new DivergenceFilter("OPEN", null, null, today, today),
                PageQuery.of(0, 20))).content()).hasSize(3);
        assertThat(asFinance2(() -> reconciliation.divergences(new DivergenceFilter("OPEN", null, null,
                today.minusDays(3), today.minusDays(1)), PageQuery.of(0, 20))).content()).isEmpty();
        // com credor: só as desse credor (as sem credor identificado ficam de fora); a de valor diferente é do DOC-1 (credor B)
        assertThat(asFinance2(() -> reconciliation.divergences(new DivergenceFilter("OPEN", null, creditorB, null, null),
                PageQuery.of(0, 20))).content()).extracting(DivergenceView::type, DivergenceView::creditorId)
                .containsExactly(tuple("AMOUNT_MISMATCH", creditorB));
        assertThat(asFinance2(() -> reconciliation.divergences(new DivergenceFilter("OPEN", null, creditorA, null, null),
                PageQuery.of(0, 20))).content()).isEmpty();
        // credor fora do escopo de quem consulta: recusado
        assertThatThrownBy(() -> as(finance1, DefaultRoles.FINANCEIRO, CreditorScope.of(Set.of(creditorB)),
                () -> reconciliation.divergences(new DivergenceFilter(null, null, creditorA, null, null), PageQuery.of(0, 20))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asFinance2(() -> reconciliation.divergences(new DivergenceFilter(null, null, null,
                today, today.minusDays(1)), PageQuery.of(0, 20)))).isInstanceOf(BusinessRuleException.class);
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

    @Test
    void statementLineDoesNotConfirmInstructionWithoutSecondPerson() {
        provision("reconcile-review");
        AgreementView agreement = formalizeAgreement(2);
        InstructionView pix = pixFor(agreement.id(), agreement.installments().getFirst().id(), "PIX_IMMEDIATE");
        String amount = pix.amount().toPlainString().replace('.', ',');
        StatementImportView imported = importCsv("extrato-review.csv", """
                data;valor;tipo;id_transacao;referencia;descricao
                %s;%s;C;;%s;PIX RECEBIDO
                """.formatted(today, amount, pix.txid()));
        assertThat(imported.createdPayments()).isEqualTo(1);
        UUID pendingId = system(() -> jdbcTemplate.queryForObject("select id from payment.payment where instruction_id = ?", UUID.class, pix.id()));
        // na fila de aprovação de outra pessoa, e não na de quem enviou
        assertThat(asFinance1(() -> payments.reviewQueue(null, PageQuery.of(0, 20))).content())
                .extracting(PaymentView::id).contains(pendingId);
        assertThat(asFinance2(() -> payments.reviewQueue(null, PageQuery.of(0, 20))).content())
                .extracting(PaymentView::id).doesNotContain(pendingId);
        assertThat(agreement(agreement.id()).installments().getFirst().status()).isNotEqualTo("PAID");

        // o PSP confirma a mesma cobrança antes da revisão: aprovar baixaria em dobro → recusado; a recusa fecha a linha
        SimulatedNotification paid = simulatePay(pix.id(), null);
        assertThat(postWebhook(paid.routingKey(), paid.body(), paid.signature())).isEqualTo(200);
        settle();
        assertThat(asOperator(() -> instructions.get(pix.id())).status()).isEqualTo("PAID");
        assertThat(asOperator(() -> instructions.get(pix.id())).paymentId()).isNotEqualTo(pendingId);
        assertThatThrownBy(() -> asFinance1(() -> payments.approve(pendingId, null, false)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("já foi paga");
        assertThat(asFinance1(() -> payments.reject(pendingId, "Já confirmado pelo PSP")).status()).isEqualTo("REJECTED");
        assertThat(lineStatus(pendingId)).isEqualTo("REVIEW_REJECTED");
        assertThat(count("select count(*) from payment.payment_allocation where payment_id = ?", pendingId)).isZero();
    }

    @Test
    void informerCannotReconcileOwnExternalPaymentByStatement() {
        provision("reconcile-self");
        PaymentView own = external("80.00", "DOC-SELF", titleB);
        assertThat(own.status()).isEqualTo("CONFIRMED");
        // F1 informou o pagamento e envia o extrato que o "confirma": não concilia, vira divergência para outra pessoa
        StatementImportView imported = importCsvAs(true, "extrato-self.csv", """
                data;valor;tipo;id_transacao;referencia;descricao
                %s;80,00;C;DOC-SELF;;TED RECEBIDA
                """.formatted(today));
        assertThat(imported.status()).isEqualTo("PROCESSED");
        assertThat(imported.divergentLines()).isEqualTo(1);
        assertThat(asFinance1(() -> payments.get(own.id())).status()).isEqualTo("CONFIRMED");
        assertThat(asFinance2(() -> reconciliation.divergences(new DivergenceFilter("OPEN", null, null, null, null),
                PageQuery.of(0, 20))).content()).extracting(DivergenceView::type, DivergenceView::paymentId)
                .containsExactly(tuple("SELF_RECONCILIATION", own.id()));
        // a divergência é resolvida por outra pessoa (quem informou continua sem poder conciliar)
        DivergenceView self = asFinance2(() -> reconciliation.divergences(new DivergenceFilter("OPEN", null, null, null,
                null), PageQuery.of(0, 20))).content().getFirst();
        assertThatThrownBy(() -> asFinance1(() -> reconciliation.resolve(self.id(), new ResolveCommand("MATCHED_PAYMENT",
                own.id(), "Conferido")))).isInstanceOf(BusinessRuleException.class).hasMessageContaining("não pode conciliá-lo");
        asFinance2(() -> reconciliation.resolve(self.id(), new ResolveCommand("MATCHED_PAYMENT", own.id(),
                "Conferido no extrato do banco")));
        assertThat(asFinance1(() -> payments.get(own.id())).status()).isEqualTo("RECONCILED");
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationEventPublisher publisher;

    /** SEG-20261124-H04 (decisão 1463): a referência anonimizada continua reconhecida pelo extrato, sem voltar em claro. */
    @Test
    void anonymizedDebtorExternalPaymentIsStillRecognizedByTheStatement() {
        provision("anon-statement");
        PaymentView paid = external("90.00", "DOC-ANON", titleB);
        assertThat(paid.status()).isEqualTo("CONFIRMED");
        UUID eventId = br.com.gestorcobranca.shared.domain.Ids.newId();
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(
                new br.com.gestorcobranca.shared.events.debt.DebtorAnonymized(eventId, tenant, Instant.now(), debtor)));
        awaitTrue("consumo do payment", () -> count("select count(*) from shared.consumed_event "
                + "where consumer = 'payment.debtor-anonymized' and event_id = ?", eventId) == 1);
        assertThat(count("select count(*) from payment.payment where external_reference = 'DOC-ANON'")).isZero();

        // enviado por F2 (F1 informou o pagamento)
        StatementImportView imported = importCsv("extrato-anon.csv", """
                data;valor;tipo;id_transacao;referencia;descricao
                %s;90,00;C;DOC-ANON;;TED RECEBIDA
                """.formatted(today));
        assertThat(imported.status()).isEqualTo("PROCESSED");
        assertThat(imported.divergentLines()).isZero();
        assertThat(lineStatus(paid.id())).isEqualTo("RECONCILED");
        assertThat(asFinance2(() -> payments.get(paid.id())).status()).isEqualTo("RECONCILED");
        assertThat(count("select count(*) from payment.payment where debtor_id = ?", debtor)).isEqualTo(1);
        // o texto não volta pela conciliação (o identificador da linha não é gravado no pagamento)
        assertThat(count("select count(*) from payment.payment where id = ? and provider_transaction_id is null",
                paid.id())).isEqualTo(1);
    }

    @Test
    void pendingStatementLineNeverHoldsThePixTransactionOfTheProvider() {
        provision("reconcile-e2e");
        AgreementView agreement = formalizeAgreement(2);
        InstructionView pix = pixFor(agreement.id(), agreement.installments().getFirst().id(), "PIX_IMMEDIATE");
        SimulatedNotification paid = simulatePay(pix.id(), null);
        String amount = pix.amount().toPlainString().replace('.', ',');
        // a linha traz o endToEndId do Pix e chega antes do PSP: o pagamento pendente não guarda o identificador
        importCsv("extrato-e2e.csv", """
                data;valor;tipo;id_transacao;referencia;descricao
                %s;%s;C;%s;%s;PIX RECEBIDO
                """.formatted(today, amount, paid.endToEndId(), pix.txid()));
        UUID pendingId = system(() -> jdbcTemplate.queryForObject("select id from payment.payment where instruction_id = ?",
                UUID.class, pix.id()));
        assertThat(asFinance1(() -> payments.get(pendingId)).providerTransactionId()).isNull();
        // a confirmação real do PSP vale normalmente, sem divergência de duplicidade
        assertThat(postWebhook(paid.routingKey(), paid.body(), paid.signature())).isEqualTo(200);
        settle();
        assertThat(asOperator(() -> instructions.get(pix.id())).status()).isEqualTo("PAID");
        assertThat(asOperator(() -> instructions.get(pix.id())).paymentId()).isNotEqualTo(pendingId);
        assertThat(count("select count(*) from payment.divergence where type = 'DUPLICATE_PAYMENT' and instruction_id = ?",
                pix.id())).isZero();
        assertThatThrownBy(() -> asFinance1(() -> payments.approve(pendingId, null, false)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("já foi paga");
        asFinance1(() -> payments.reject(pendingId, "Já confirmado pelo PSP"));
        assertThat(count("select count(*) from payment.payment where provider_transaction_id = ?", paid.endToEndId()))
                .isEqualTo(1);
    }
}
