package br.com.gestorcobranca.payment.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Instruções de pagamento (F10): Pix imediato ou com vencimento para uma parcela de acordo ou um
 * título avulso ({@code PAYMENT_INSTRUCTION_CREATE}), com expiração. O registro no PSP é assíncrono:
 * a instrução nasce {@code PENDING} e passa a {@code ACTIVE} com o "copia e cola" (conteúdo do QR
 * Code). Boleto e link só têm as portas ({@code BoletoProvider}, {@code PaymentLinkProvider}): sem
 * provedor definido (issue #12) a criação é recusada. Uma instrução aberta do mesmo tipo e valor para
 * o mesmo destino é devolvida em vez de duplicada.
 */
public interface PaymentInstructionUseCases {

    InstructionView create(CreateInstructionCommand command, String idempotencyKey);

    InstructionView get(UUID instructionId);

    PageResponse<InstructionView> search(InstructionFilter filter, PageQuery page);

    InstructionView cancel(UUID instructionId, String reason);

    /**
     * @param type              PIX_IMMEDIATE, PIX_DUE, BOLETO ou LINK
     * @param amount            nulo = saldo em aberto da parcela/título (nunca acima dele)
     * @param dueDate           vencimento (PIX_DUE); nulo = vencimento da parcela
     * @param expiresInSeconds  validade do Pix imediato; nulo = parâmetro da assessoria
     * @param channel           canal pelo qual será enviada (informativo, para métricas)
     */
    record CreateInstructionCommand(String type, UUID agreementId, UUID installmentId, UUID titleId, BigDecimal amount,
                                    LocalDate dueDate, Integer expiresInSeconds, String channel) {
    }

    record InstructionFilter(UUID creditorId, UUID debtorId, UUID agreementId, UUID titleId, String status) {
    }

    /**
     * @param pixCopyPaste  BR Code ("copia e cola"); também é o conteúdo a renderizar como QR Code
     */
    record InstructionView(UUID id, String type, String status, UUID creditorId, UUID portfolioId, UUID debtorId,
                           UUID caseId, UUID agreementId, UUID installmentId, Integer installmentNumber, UUID titleId,
                           BigDecimal amount, LocalDate dueDate, Instant expiresAt, String txid, String pixCopyPaste,
                           String location, String failureCode, UUID paymentId, Instant paidAt, Instant cancelledAt,
                           String cancelReason, String channel, Instant createdAt, long version) {
    }
}
