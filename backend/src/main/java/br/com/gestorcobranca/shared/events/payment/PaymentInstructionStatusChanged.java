package br.com.gestorcobranca.shared.events.payment;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mudança de situação de uma instrução de pagamento (registro no PSP, recusa, pagamento, expiração,
 * cancelamento). Leva só ids, enums e valores — nunca o "copia e cola", o txid ou dados do pagador: o
 * consumidor que precisa do conteúdo consulta {@code PaymentInstructionUseCases#get} com a própria
 * permissão e escopo. Uso: o assistente envia o Pix ao devedor quando a cobrança fica {@code ACTIVE} e
 * mede a conversão Pix emitido → pago ({@code docs/tecnico/desenho/assistente.md} §5.1).
 *
 * @param type      PIX_IMMEDIATE, PIX_DUE, BOLETO ou LINK
 * @param status    nova situação: ACTIVE, PAID, EXPIRED, CANCELLED ou FAILED
 * @param previous  situação anterior
 * @param paidAt    data do pagamento ({@code PAID})
 */
public record PaymentInstructionStatusChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID instructionId,
        String type,
        String previous,
        String status,
        UUID creditorId,
        UUID debtorId,
        UUID agreementId,
        UUID installmentId,
        BigDecimal amount,
        Instant paidAt) implements IntegrationEvent {
}
