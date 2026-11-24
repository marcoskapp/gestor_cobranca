package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.domain.model.InstructionStatus;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.model.ReconciliationMode;
import br.com.gestorcobranca.payment.domain.model.StatementLineStatus;
import br.com.gestorcobranca.payment.domain.repository.BankStatementRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Revisão do pagamento criado por linha de extrato enviada por uma pessoa (SEG-20261124-C01, decisão 1450): na
 * aprovação por outra pessoa, a instrução citada é bloqueada e paga, e o pagamento é conciliado com a linha (a
 * conferência da linha é a própria aprovação); na recusa, a linha fica {@code REVIEW_REJECTED}. Sempre dentro da
 * transação da revisão, com o pagamento já bloqueado.
 */
@Component
class StatementLineReview {

    private final PaymentInstructionRepository instructions;
    private final BankStatementRepository statements;
    private final PaymentRepository payments;
    private final PaymentRecorder recorder;

    StatementLineReview(PaymentInstructionRepository instructions, BankStatementRepository statements,
                        PaymentRepository payments, PaymentRecorder recorder) {
        this.instructions = instructions;
        this.statements = statements;
        this.payments = payments;
        this.recorder = recorder;
    }

    /**
     * Antes de bloquear o pagamento: bloqueia a instrução citada pelo pagamento de extrato, na mesma ordem do
     * {@code StatementMatcher} e do {@code PixSettlement} (instrução → pagamento), sem carregar o pagamento.
     */
    Optional<PaymentInstruction> lockInstructionOf(UUID paymentId) {
        return payments.findStatementReviewInstructionId(paymentId).flatMap(instructions::findByIdForUpdate);
    }

    /**
     * Instrução a pagar na aprovação (já bloqueada por {@link #lockInstructionOf}); nula se o pagamento não veio de
     * linha de extrato.
     *
     * @throws BusinessRuleException se a instrução já foi paga por outro pagamento (o PSP confirmou antes): este
     *                               precisa ser recusado, nunca confirmado em dobro
     */
    PaymentInstruction instructionForApproval(Payment p, PaymentInstruction locked) {
        if (!p.isFromStatementReview()) {
            return null;
        }
        Guard.requireState(locked != null && locked.getId().equals(p.getInstructionId()), "payment.instruction_not_found",
                "Cobrança citada no extrato não encontrada");
        PaymentInstruction instruction = locked;
        if (instruction.getStatus() == InstructionStatus.PAID && !Objects.equals(instruction.getPaymentId(), p.getId())) {
            throw new BusinessRuleException("payment.instruction_already_paid",
                    "A cobrança citada no extrato já foi paga por outro pagamento. Recuse este para não baixar em dobro");
        }
        return instruction;
    }

    /** Depois da confirmação: concilia com a linha do extrato, por quem aprovou. */
    void approved(Payment p, UUID reviewer, Instant now) {
        if (!p.isFromStatementReview() || p.getStatementLineId() == null) {
            return;
        }
        statements.findLine(p.getStatementLineId()).ifPresent(line -> {
            String txId = line.getTransactionId();
            // o identificador da linha só agora, e só se nenhum outro pagamento já o tem (o PSP confirma pelo e2e)
            if (txId != null && p.getProviderTransactionId() == null
                    && payments.findByProviderTransactionId(txId).isEmpty()) {
                p.attachProviderTransaction(txId);
            }
            if (p.reconcile(ReconciliationMode.STATEMENT, reviewer, line.getId(), now)) {
                recorder.reconciled(p, ReconciliationMode.STATEMENT, line.getImportId());
            }
            payments.save(p);
            line.outcome(StatementLineStatus.CREATED_PAYMENT, p.getId());
            statements.save(line);
        });
    }

    void rejected(Payment p) {
        if (!p.isFromStatementReview() || p.getStatementLineId() == null) {
            return;
        }
        statements.findLine(p.getStatementLineId()).ifPresent(line -> {
            line.outcome(StatementLineStatus.REVIEW_REJECTED, p.getId());
            statements.save(line);
        });
    }
}
