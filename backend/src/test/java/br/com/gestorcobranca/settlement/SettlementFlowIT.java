package br.com.gestorcobranca.settlement;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReverseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementUpload;
import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptDetailView;
import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptFilter;
import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptView;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.RuleSetCommand;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.RuleSetView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementExportUseCases.ExportFile;
import br.com.gestorcobranca.settlement.application.port.in.SettlementExportUseCases.ExportView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.AdjustmentCommand;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.AdjustmentView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.CreateStatementCommand;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.SendCommand;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementView;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.TransferCommand;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.TransferView;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.HonorariumPolicy;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.RateRule;
import br.com.gestorcobranca.settlement.domain.model.rules.PaymentType;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxo completo contra PostgreSQL real: regras publicadas com dupla checagem → pagamento de parcela de
 * acordo (payment → negotiation → AgreementPaymentRecorded) → recebimento e razão com memória →
 * demonstrativo (pendente até a conciliação pelo extrato) → ajustes com segunda aprovação → aprovação
 * segregada → demonstrativo imutável → exportações com hash → envio e aceite → repasse com comprovante
 * (concorrência e idempotência) → estorno depois do fechamento entra no demonstrativo seguinte.
 */
class SettlementFlowIT extends SettlementTestSupport {

    static CommissionRules rulesA() {
        return new CommissionRules(new BigDecimal("10"), List.of(new RateRule("PARCELADO_ATRASO",
                "Parcelado com mais de 30 dias de atraso", 0, null, 31, null, PaymentType.INSTALLMENTS, null, null,
                new BigDecimal("12"))), new BigDecimal("20.00"), HonorariumPolicy.agencyFull(), true);
    }

    @Test
    void paymentLedgerSegregatedApprovalExportTransferAndReversalInNextPeriod() throws Exception {
        provision("settle");

        // regras: quem editou não publica
        RuleSetView draft = asFinance1(() -> ruleSets.createDraft(new RuleSetCommand(contractA, rulesA(),
                "Contrato A, cláusula 5")));
        assertThatThrownBy(() -> asFinance1(() -> ruleSets.publish(draft.id(), LocalDate.of(2026, 1, 1))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("conferência");
        RuleSetView published = asFinance2(() -> ruleSets.publish(draft.id(), LocalDate.of(2026, 1, 1)));
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.rules().rateRules()).hasSize(1);

        // pagamento da 1ª parcela de um acordo de 2 parcelas, informado e revisado por outra pessoa
        AgreementView agreement = formalizeAgreement(2);
        InstallmentView first = agreement.installments().getFirst();
        BigDecimal amount = first.amount();
        Instant paidAt = Instant.now().minus(3, ChronoUnit.DAYS);
        LocalDate paidOn = paidAt.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        PaymentView informed = asFinance1(() -> payments.registerExternal(new ExternalPaymentCommand(creditorA, debtor,
                amount, paidAt, "TRANSFER", "AGENCY_ACCOUNT", "TED-1", null,
                new TargetInput("AGREEMENT", agreement.id(), List.of(first.id()), null)), pdf(), null));
        if ("INFORMED".equals(informed.status())) {
            asFinance2(() -> payments.approve(informed.id(), "Comprovante conferido"));
        }
        PaymentView payment = asFinance1(() -> payments.get(informed.id()));
        assertThat(payment.status()).isEqualTo("CONFIRMED");
        UUID allocation = payment.allocations().getFirst().id();
        awaitTrue("recebimento apurado", () -> count("select count(*) from settlement.receipt where allocation_id = ? "
                + "and kind = 'PAYMENT' and rating_status = 'RATED'", allocation) == 1);

        ReceiptView receipt = asFinance1(() -> ledger.receipts(new ReceiptFilter(creditorA, agreement.id(), null, null,
                null, null), PageQuery.of(0, 10))).content().getFirst();
        BigDecimal honorarium = receipt.honorarium();
        BigDecimal commission = amount.subtract(honorarium).multiply(new BigDecimal("0.12"))
                .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal effect = amount.subtract(honorarium).subtract(commission).subtract(new BigDecimal("20.00"));
        assertThat(receipt.amount()).isEqualByComparingTo(amount);
        assertThat(receipt.paymentType()).isEqualTo("INSTALLMENTS");
        assertThat(receipt.daysPastDue()).isEqualTo(60);
        assertThat(receipt.receivedBy()).isEqualTo("AGENCY");
        assertThat(receipt.paidOn()).isEqualTo(paidOn);
        assertThat(receipt.contractId()).isEqualTo(contractA);
        assertThat(receipt.entry().ruleVersion()).isEqualTo(1);
        assertThat(receipt.entry().calculation().ruleCode()).isEqualTo("PARCELADO_ATRASO");
        assertThat(receipt.entry().calculation().commission()).isEqualByComparingTo(commission);
        assertThat(receipt.entry().calculation().fixedFee()).isEqualByComparingTo("20.00");
        assertThat(receipt.entry().calculation().agencyHonorarium()).isEqualByComparingTo(honorarium);
        assertThat(receipt.entry().calculation().transferEffect()).isEqualByComparingTo(effect);
        assertThat(receipt.entry().calculation().memory()).extracting(m -> m.step()).contains("COMMISSION_BASE",
                "COMMISSION", "FIXED_FEE", "TRANSFER_EFFECT");
        ReceiptDetailView detail = asFinance1(() -> ledger.receipt(receipt.id()));
        assertThat(detail.detail().toString()).contains("INSTALLMENT", "T-60/1", "T-30/2");
        assertThat(count("select count(*) from shared.business_event where event_type = 'commission.accrued' "
                + "and tenant_id = ?", tenant)).isEqualTo(1);

        // demonstrativo do período encerrado ontem: pagamento ainda não conciliado fica pendente
        LocalDate start = today.minusDays(10);
        LocalDate end = today.minusDays(1);
        StatementView created = asFinance1(() -> statements.create(new CreateStatementCommand(creditorA, start, end)));
        assertThatThrownBy(() -> asFinance1(() -> statements.create(new CreateStatementCommand(creditorA, null, null))))
                .isInstanceOf(ConflictException.class);
        StatementView pending = asFinance1(() -> statements.calculate(created.id()));
        assertThat(pending.status()).isEqualTo("CALCULATED");
        assertThat(pending.totals().receipts()).isZero();
        assertThat(pending.totals().pendingReconciliation()).isEqualByComparingTo(amount);
        assertThat(pending.totals().netTransfer()).isEqualByComparingTo("0.00");
        assertThat(pending.totals().negotiated()).isEqualByComparingTo("0.00");
        assertThat(pending.totals().forecastCommission()).isPositive();
        assertThat(pending.calculation().get("pendingReconciliation").toString()).contains(allocation.toString());

        // conciliação pelo extrato (id_transacao = referência do pagamento externo)
        String csv = "data;valor;tipo;id_transacao;referencia;descricao\n%s;%s;C;TED-1;;TED RECEBIDA\n"
                .formatted(paidOn, amount.toPlainString().replace('.', ','));
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        asFinance2(() -> reconciliation.importStatement(new StatementUpload("CSV_GENERIC", null, "extrato.csv",
                "text/csv", bytes.length, new ByteArrayInputStream(bytes))));
        drainJobs("payment.");
        awaitTrue("pagamento conciliado", () -> "RECONCILED".equals(asFinance1(() -> payments.get(payment.id())).status()));

        StatementView calculated = asFinance1(() -> statements.calculate(created.id()));
        assertThat(calculated.totals().receipts()).isEqualTo(1);
        assertThat(calculated.totals().receivedByAgency()).isEqualByComparingTo(amount);
        assertThat(calculated.totals().recovered()).isEqualByComparingTo(amount);
        assertThat(calculated.totals().reconciled()).isEqualByComparingTo(amount);
        assertThat(calculated.totals().commission()).isEqualByComparingTo(commission);
        assertThat(calculated.totals().fixedFees()).isEqualByComparingTo("20.00");
        assertThat(calculated.totals().agencyHonorarium()).isEqualByComparingTo(honorarium);
        assertThat(calculated.totals().pendingReconciliation()).isEqualByComparingTo("0.00");
        assertThat(calculated.totals().netTransfer()).isEqualByComparingTo(effect);

        // ajustes: abaixo do limite aplica na hora; acima exige segunda aprovação
        AdjustmentView small = asAdmin(() -> statements.requestAdjustment(created.id(), new AdjustmentCommand("OTHER",
                new BigDecimal("-15.00"), "Tarifa bancária do repasse do período", null)));
        assertThat(small.status()).isEqualTo("APPLIED");
        AdjustmentView big = asFinance1(() -> statements.requestAdjustment(created.id(), new AdjustmentCommand(
                "COMMISSION_CORRECTION", new BigDecimal("1500.00"), "Bonificação contratual por meta trimestral", null)));
        assertThat(big.status()).isEqualTo("PENDING_SECOND_APPROVAL");
        asFinance1(() -> statements.review(created.id()));
        assertThatThrownBy(() -> asManager(() -> statements.approve(created.id())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("segunda aprovação");
        assertThatThrownBy(() -> asFinance1(() -> statements.approveAdjustment(big.id(), null)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(asManager(() -> statements.rejectAdjustment(big.id(), "Sem previsão contratual")).status())
                .isEqualTo("REJECTED");

        // segregação: quem pediu ajuste (admin) ou calculou não aprova; financeiro não tem a permissão
        assertThatThrownBy(() -> asAdmin(() -> statements.approve(created.id())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("ajuste");
        assertThatThrownBy(() -> asFinance1(() -> statements.approve(created.id())))
                .isInstanceOf(ScopeAccessDeniedException.class);
        StatementView approved = asManager(() -> statements.approve(created.id()));
        BigDecimal net = effect.subtract(new BigDecimal("15.00"));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.totals().netTransfer()).isEqualByComparingTo(net);
        assertThat(approved.totals().outstanding()).isEqualByComparingTo(net);
        assertThat(count("select count(*) from shared.business_event where event_type = 'settlement.closed' "
                + "and tenant_id = ?", tenant)).isEqualTo(1);

        // aprovado é imutável no banco; razão é append-only
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update settlement.statement set commission_amount = 0 where id = ?", created.id())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update settlement.commission_entry set commission_amount = 0"))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("delete from settlement.receipt")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asFinance1(() -> statements.calculate(created.id())))
                .isInstanceOf(ConflictException.class);

        // exportações conciliáveis guardadas com hash
        ExportView csvExport = asFinance1(() -> exports.export(created.id(), "CSV"));
        ExportView xlsxExport = asFinance1(() -> exports.export(created.id(), "XLSX"));
        ExportView pdfExport = asFinance1(() -> exports.export(created.id(), "PDF"));
        ExportFile file = asFinance1(() -> exports.download(csvExport.id()));
        byte[] content;
        try (var in = file.content()) {
            content = in.readAllBytes();
        }
        assertThat(sha256(content)).isEqualTo(csvExport.sha256());
        String text = new String(content, StandardCharsets.UTF_8);
        assertThat(text).startsWith("tipo_registro;demonstrativo_id;credor_id");
        assertThat(text).contains("RECEBIMENTO;" + created.id(), allocation.toString(), "DETALHE;", "T-60/1",
                "PARCELADO_ATRASO", "AJUSTE;");
        String netLine = text.lines().filter(l -> l.contains(";LIQUIDO_A_REPASSAR;")).findFirst().orElseThrow();
        assertThat(netLine.split(";", -1)[18]).isEqualTo(net.setScale(2).toPlainString());
        try (var in = asFinance1(() -> exports.download(pdfExport.id())).content()) {
            assertThat(new String(in.readNBytes(5), StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        }
        assertThat(xlsxExport.fileSize()).isPositive();
        assertThat(count("select count(*) from shared.access_log where event_type = 'DATA_EXPORTED' and tenant_id = ?",
                tenant)).isGreaterThanOrEqualTo(3);
        assertThat(count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED' and tenant_id = ?",
                tenant)).isGreaterThanOrEqualTo(2);

        // envio e aceite do credor
        assertThat(asFinance1(() -> statements.send(created.id(), new SendCommand("EMAIL", "financeiro do Banco A",
                List.of(csvExport.id(), pdfExport.id())))).status()).isEqualTo("SENT");
        assertThat(asFinance1(() -> statements.acknowledge(created.id(), "Recebido e conferido pelo credor")).status())
                .isEqualTo("ACKNOWLEDGED");

        // repasse: dois registros concorrentes do valor total → só um passa
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<TransferView> register = () -> asFinance1(() -> transfers.register(new TransferCommand(created.id(), net,
                today, "TED 001", null), transferPdf(), null));
        List<Future<TransferView>> results = pool.invokeAll(List.of(register, register));
        pool.shutdown();
        int ok = 0;
        TransferView done = null;
        for (Future<TransferView> f : results) {
            try {
                done = f.get();
                ok++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BusinessRuleException.class);
            }
        }
        assertThat(ok).isEqualTo(1);
        assertThat(done.direction()).isEqualTo("AGENCY_TO_CREDITOR");
        assertThat(asFinance1(() -> statements.get(created.id())).totals().outstanding()).isEqualByComparingTo("0.00");
        TransferView cancelled = done;
        asFinance1(() -> transfers.cancel(cancelled.id(), "Comprovante de outra operação"));
        assertThat(asFinance1(() -> transfers.balances(creditorA)).getFirst().toTransfer()).isEqualByComparingTo(net);
        // idempotência: mesma chave e conteúdo → o mesmo repasse
        TransferView once = asFinance1(() -> transfers.register(new TransferCommand(created.id(), net, today, "TED 002",
                null), transferPdf(), "repasse-1"));
        TransferView twice = asFinance1(() -> transfers.register(new TransferCommand(created.id(), net, today, "TED 002",
                null), transferPdf(), "repasse-1"));
        assertThat(twice.id()).isEqualTo(once.id());
        assertThat(asFinance1(() -> transfers.balances(creditorA))).isEmpty();
        try (var in = asFinance1(() -> transfers.openEvidence(once.id())).content()) {
            assertThat(in.readAllBytes()).isEqualTo(PDF);
        }

        // estorno depois do fechamento: compensação entra no demonstrativo seguinte, o aprovado não muda
        asFinance2(() -> payments.reverse(payment.id(), new ReverseCommand("CHARGEBACK", "Contestação do pagador")));
        awaitTrue("estorno apurado", () -> count("select count(*) from settlement.receipt where allocation_id = ? "
                + "and kind = 'REVERSAL' and rating_status = 'RATED'", allocation) == 1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'commission.reversed' "
                + "and tenant_id = ?", tenant)).isEqualTo(1);
        clock.shift(Duration.ofDays(2));
        StatementView next = asFinance1(() -> statements.create(new CreateStatementCommand(creditorA, today,
                today.plusDays(1))));
        assertThat(next.periodStart()).isEqualTo(today);
        StatementView nextCalculated = asFinance1(() -> statements.calculate(next.id()));
        assertThat(nextCalculated.totals().receipts()).isEqualTo(1);
        assertThat(nextCalculated.totals().reversed()).isEqualByComparingTo(amount.negate());
        assertThat(nextCalculated.totals().commission()).isEqualByComparingTo(commission.negate());
        assertThat(nextCalculated.totals().fixedFees()).isEqualByComparingTo("-20.00");
        assertThat(nextCalculated.totals().netTransfer()).isEqualByComparingTo(effect.negate());
        assertThat(asFinance1(() -> statements.get(created.id())).totals().netTransfer()).isEqualByComparingTo(net);
        assertThat(count("select count(*) from shared.audit_event where action like 'settlement_statement.%' "
                + "and tenant_id = ?", tenant)).isGreaterThanOrEqualTo(8);
    }

    private static String sha256(byte[] content) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
