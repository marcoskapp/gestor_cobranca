package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.domain.model.BankStatementLine;
import br.com.gestorcobranca.payment.domain.model.Divergence;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.PaymentReversal;
import br.com.gestorcobranca.payment.domain.repository.BankStatementRepository;
import br.com.gestorcobranca.payment.domain.repository.DivergenceRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentReversalRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LGPD (SEG-20261124-D01, decisões 1456 e 1458): devedor anonimizado ({@code DebtorAnonymized}) — dos pagamentos dele, o
 * comprovante é apagado do armazenamento <em>dentro</em> da transação (a falha desfaz tudo e o evento é reprocessado; o
 * SHA-256 fica), e saem os textos livres: observação de quem informou, nota da revisão, descrição do banco nas linhas de
 * extrato ligadas, observação do estorno, justificativa da resolução das divergências ligadas e motivo do cancelamento
 * das instruções do devedor. Valores, datas, situação, alocações e a trilha ficam (prestação de contas e auditoria).
 * Idempotente.
 */
@Component
class PaymentAnonymizer {

    private final PaymentRepository payments;
    private final BankStatementRepository statements;
    private final PaymentReversalRepository reversals;
    private final DivergenceRepository divergences;
    private final PaymentInstructionRepository instructions;
    private final FileCleanup fileCleanup;
    private final PaymentMetrics metrics;
    private final AuditTrail auditTrail;
    private final PaymentReferenceDigest referenceDigest;

    PaymentAnonymizer(PaymentRepository payments, BankStatementRepository statements, PaymentReversalRepository reversals,
                      DivergenceRepository divergences, PaymentInstructionRepository instructions,
                      FileCleanup fileCleanup, PaymentMetrics metrics, AuditTrail auditTrail,
                      PaymentReferenceDigest referenceDigest) {
        this.referenceDigest = referenceDigest;
        this.payments = payments;
        this.statements = statements;
        this.reversals = reversals;
        this.divergences = divergences;
        this.instructions = instructions;
        this.fileCleanup = fileCleanup;
        this.metrics = metrics;
        this.auditTrail = auditTrail;
    }

    void anonymize(UUID debtorId, UUID sourceEventId) {
        List<Payment> all = payments.findByDebtorId(debtorId);
        List<String> keys = new ArrayList<>();
        for (Payment p : all) {
            String key = p.anonymize(ref -> referenceDigest.digest(p.getTenantId(), ref));
            payments.save(p);
            if (key != null) {
                keys.add(key);
            }
        }
        List<UUID> ids = all.stream().map(Payment::getId).toList();
        if (!ids.isEmpty()) {
            for (BankStatementLine line : statements.findLinesByPaymentIds(ids)) {
                if (line.anonymizeDescription()) {
                    statements.save(line);
                }
            }
            for (PaymentReversal reversal : reversals.findByPaymentIdIn(ids)) {
                if (reversal.anonymizeNote()) {
                    reversals.save(reversal);
                }
            }
            for (Divergence divergence : divergences.findByPayments(ids)) {
                if (divergence.anonymizeResolutionNote()) {
                    divergences.save(divergence);
                }
            }
        }
        for (PaymentInstruction instruction : instructions.findByDebtorId(debtorId)) {
            if (instruction.anonymizeCancelReason()) {
                instructions.save(instruction);
            }
        }
        // erros de banco aparecem antes de apagar arquivos; a falha ao apagar desfaz tudo (evento reprocessado)
        payments.flush();
        keys.forEach(fileCleanup::deleteNow);
        metrics.debtorAnonymized(keys.size());
        auditTrail.record(AuditEntry.of("payment.debtor_anonymized", "debtor", debtorId)
                .meta("sourceEventId", sourceEventId).meta("payments", all.size()).meta("evidencesRemoved", keys.size()));
    }
}
