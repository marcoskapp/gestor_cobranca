package br.com.gestorcobranca.assistant.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Pix da parcela de acordo gerado pelo assistente na conversa (ferramenta {@code generate_payment}).
 * Adaptador sobre a interface pública do payment ({@code PaymentInstructionUseCases}) — ADR-0009. Sempre
 * <strong>Pix imediato</strong> (cobrança {@code cob} do PSP da conta Pix do credor/assessoria, que não
 * envia dados do pagador ao PSP), executado como o ator {@code ASSISTANT} do turno, com escopo no credor da
 * conversa. Uma instrução aberta igual (mesma parcela e valor) é reutilizada pelo payment.
 */
public interface PaymentInstructionPort {

    /**
     * @param amount          valor exato da parcela calculado pelo motor (nunca do modelo)
     * @param idempotencyKey  chave por turno e parcela (reprocessamento não duplica a cobrança)
     */
    PixInstruction issueImmediatePix(IssuePixCommand command);

    Optional<PixInstruction> find(UUID instructionId);

    void cancel(UUID instructionId, String reason);

    record IssuePixCommand(UUID agreementId, UUID installmentId, BigDecimal amount, int expiresInSeconds, String channel,
                           String idempotencyKey) {
    }

    /**
     * @param status     PENDING (aguardando o PSP), ACTIVE, PAID, EXPIRED, CANCELLED ou FAILED
     * @param copyPaste  BR Code "copia e cola" (só depois de ACTIVE; enviado ao devedor, nunca ao modelo)
     * @param reused     instrução aberta igual já existia e foi devolvida
     */
    record PixInstruction(UUID instructionId, String type, String status, UUID creditorId, UUID debtorId,
                          UUID agreementId, UUID installmentId, Integer installmentNumber, BigDecimal amount,
                          Instant expiresAt, String copyPaste, Instant createdAt, Instant paidAt, boolean reused) {

        public boolean open() {
            return "PENDING".equals(status) || "ACTIVE".equals(status);
        }
    }
}
