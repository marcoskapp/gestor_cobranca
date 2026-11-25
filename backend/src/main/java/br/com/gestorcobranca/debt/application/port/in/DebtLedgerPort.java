package br.com.gestorcobranca.debt.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * <strong>Porta interna</strong> (sem endpoint web) para os módulos payment, negotiation e
 * collection lançarem fatos financeiros e de contestação no razão do título (RB03, RB06).
 * <p>
 * Garantias:
 * <ul>
 *   <li>o título é bloqueado ({@code SELECT ... FOR UPDATE}) durante o lançamento: operações
 *       concorrentes no mesmo título são serializadas;</li>
 *   <li>idempotência por (título, tipo de lançamento, referência): repetir a mesma chamada
 *       retorna {@code applied = false} sem novo efeito; repetir com valores diferentes é
 *       conflito ({@code ledger.reference_conflict});</li>
 *   <li>créditos (pagamento, desconto, baixa) não podem exceder o saldo do componente;</li>
 *   <li>saldo zerado quita o título; estorno que reabre saldo reabre o título.</li>
 * </ul>
 * Exige que o credor do título esteja no escopo do ator (consumidores de evento executam como
 * sistema, com escopo total).
 */
public interface DebtLedgerPort {

    LedgerResult applyPayment(PaymentApplication payment);

    /** Lança estornos compensatórios espelhando os lançamentos do pagamento, que permanecem no razão. */
    LedgerResult reversePayment(PaymentReversal reversal);

    /** Juros, multa, encargos ou honorários reconhecidos (ex.: num acordo); não aceita principal. */
    LedgerResult applyCharges(ChargeApplication charges);

    /** Desconto ou baixa concedidos na quitação de acordo. */
    LedgerResult applyDiscount(DiscountApplication discount);

    /**
     * Vincula títulos a uma contestação (F21). Com {@code blocksCollection}, os títulos saem de
     * réguas e negociações até a resolução.
     */
    void markDisputed(Collection<UUID> titleIds, UUID disputeId, boolean blocksCollection);

    /** Remove o vínculo com a contestação (vínculos com outra contestação são preservados). */
    void clearDispute(Collection<UUID> titleIds, UUID disputeId);

    /** Valores por componente da dívida (nulos valem zero; informe ao menos um positivo). */
    record ComponentAmounts(BigDecimal principal, BigDecimal interest, BigDecimal fine, BigDecimal fees,
                            BigDecimal honorarium) {

        public static ComponentAmounts principalOnly(BigDecimal principal) {
            return new ComponentAmounts(principal, null, null, null, null);
        }
    }

    /**
     * @param receivedBy quem recebeu o dinheiro (H07, decisão 1466): {@link Receiver#CREDITOR} no pagamento feito
     *                   diretamente ao credor; o título mostra o selo "Pago ao credor" enquanto houver valor líquido
     *                   recebido por ele. O estorno repete o receptor do pagamento estornado.
     */
    record PaymentApplication(UUID titleId, UUID paymentId, ComponentAmounts parts, Instant paidAt, Receiver receivedBy) {

        public PaymentApplication {
            receivedBy = receivedBy == null ? Receiver.AGENCY : receivedBy;
        }

        /** Pagamento recebido pela assessoria. */
        public PaymentApplication(UUID titleId, UUID paymentId, ComponentAmounts parts, Instant paidAt) {
            this(titleId, paymentId, parts, paidAt, Receiver.AGENCY);
        }
    }

    /** Quem recebeu o dinheiro de um pagamento. */
    enum Receiver {
        /** Conta da assessoria ou meio integrado. */
        AGENCY,
        /** Pago diretamente ao credor (informado por ele, pela integração dele ou por quem registrou). */
        CREDITOR
    }

    record PaymentReversal(UUID titleId, UUID paymentId, Instant reversedAt, String reason) {
    }

    /** @param referenceType origem do reconhecimento (ex.: {@code agreement}) */
    record ChargeApplication(UUID titleId, String referenceType, UUID referenceId, ComponentAmounts parts,
                             Instant occurredAt, String reason) {
    }

    record DiscountApplication(UUID titleId, UUID agreementId, ConcessionType type, ComponentAmounts parts,
                               Instant occurredAt, String reason) {
    }

    enum ConcessionType {
        /** Desconto negociado. */
        DISCOUNT,
        /** Baixa (perdão) do saldo remanescente. */
        WRITE_OFF
    }

    /**
     * @param applied        falso quando a operação já havia sido lançada (repetição idempotente)
     * @param previousStatus status antes da operação
     */
    record LedgerResult(UUID titleId, boolean applied, String previousStatus, String status, BigDecimal openBalance,
                        BigDecimal paidTotal) {
    }
}
